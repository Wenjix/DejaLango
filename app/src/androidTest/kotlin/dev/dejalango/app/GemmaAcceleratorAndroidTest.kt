package dev.dejalango.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.app.platform.audio.Wav
import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.generateText
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * Gemma 4 on the phone, through the app's own wiring ([dev.dejalango.app.di.AppGraph.createSpeechModel]) and the default
 * AUTO backend: with this phone's accelerator bundle pushed, Gemma loads on that accelerator and runs a Stage A turn
 * from the demo fixture. Logs load time, prefill and decode speed. Skipped without the bundle.
 *
 * Run it with `am instrument`, never with connectedAndroidTest: that uninstalls the app afterwards, and the pushed
 * models with it.
 */
@RunWith(AndroidJUnit4::class)
class GemmaAcceleratorAndroidTest {

    @Test
    fun autoRunsGemmaOnThisPhonesAccelerator() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = ctx.appGraph
        val soc = graph.modelFiles.gemmaSoc
        assumeTrue("no Gemma accelerator bundle in ${graph.modelFiles.dir}", soc != null)
        val cfg = PipelineConfig()
        val prompts = PromptBuilder(graph.promptSet)
        val model = graph.createSpeechModel(cfg, useFake = false)
        try {
            val t0 = SystemClock.elapsedRealtime()
            model.warm(prompts.system(cfg, null))
            Log.i(TAG, "loaded in ${SystemClock.elapsedRealtime() - t0} ms: ${model.info}")

            // The fixture's first five seconds, the first Mandarin question.
            val wav = Wav.decode(ctx.assets.open("fixtures/demo_zh.wav").use { it.readBytes() })
            val pcm = Wav.resample(wav.samples, wav.sampleRate, 16_000).let { it.copyOf(minOf(it.size, 5 * 16_000)) }
            val turn = AudioTurn(pcm, 0L, pcm.size * 1000L / 16_000, 16_000)
            val t1 = SystemClock.elapsedRealtime()
            val gen = model.generateText(GenerateRequest(audio = turn, text = prompts.stageA(cfg), maxTokens = 160, tag = RequestTag.STAGE_A))
            Log.i(TAG, "stage A in ${SystemClock.elapsedRealtime() - t1} ms (prefill ${gen.stats?.prefillMs} ms, ${gen.stats?.outputTokens} tokens at ${gen.stats?.decodeTokensPerSec} tok/s): ${gen.text.take(200)}")

            assertEquals("AUTO should land on ${soc!!.name}, got ${model.info}", soc.absolutePath, model.info.file)
        } finally {
            model.close()
        }
    }

    @Test
    fun theEmbedderKeepsTheTpuBesideGemma() = runBlocking<Unit> {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val embedderSoc = graph.modelFiles.embedderSoc
        assumeTrue("no Gemma accelerator bundle in ${graph.modelFiles.dir}", graph.modelFiles.gemmaSoc != null)
        assumeTrue("no EmbeddingGemma accelerator bundle in ${graph.modelFiles.dir}", embedderSoc != null)
        val cfg = PipelineConfig()
        val model = graph.createSpeechModel(cfg, useFake = false)
        val embedder = graph.createEmbedder(useFake = false)
        try {
            // PipelineRuntime's order: Gemma first, then the embedder.
            model.warm(PromptBuilder(graph.promptSet).system(cfg, null))
            embedder.warm()
            Log.i(TAG, "beside Gemma on ${model.info.backend}: embedder ${embedder.description}")
            val query = "Is this your first time in Beijing?"
            val before = embedder.embed(query, EmbedRole.QUERY)
            val ms = List(20) { measureNanoTime { embedder.embed(query, EmbedRole.QUERY) } / 1e6 }.sorted()
            Log.i(TAG, "query embedding beside Gemma, 20 runs: median %.1f ms, max %.1f ms".format(ms[ms.size / 2], ms.last()))

            // A turn runs Gemma between two embeddings; the TPU must give the embedder the same answer afterwards.
            model.generateText(GenerateRequest(text = "Say hello in Mandarin.", maxTokens = 16, tag = RequestTag.STAGE_B))
            val after = embedder.embed(query, EmbedRole.QUERY)
            Log.i(TAG, "same query after a Gemma generation: cosine %.4f".format(Vectors.cosine(before, after)))
            assertTrue("embedding changed after Gemma ran: ${Vectors.cosine(before, after)}", Vectors.cosine(before, after) > 0.99f)

            assertTrue("expected ${embedderSoc!!.name} beside Gemma, got: ${embedder.description}", embedder.description.contains(embedderSoc.nameWithoutExtension))
        } finally {
            embedder.close()
            model.close()
        }
    }

    private companion object {
        const val TAG = "GemmaAcceleratorAndroidTest"
    }
}

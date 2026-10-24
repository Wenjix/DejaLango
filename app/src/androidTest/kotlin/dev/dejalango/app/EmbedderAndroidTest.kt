package dev.dejalango.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * EmbeddingGemma on the phone, through the app's own wiring ([dev.dejalango.app.di.AppGraph.createEmbedder]): its vectors
 * carry meaning across languages, which the hashing fallback's can't, and the build compiled for this phone's
 * accelerator wins when it is on the device. Skipped until a bundle is pushed (scripts/push_models.sh).
 *
 * Run it with `am instrument`, never with connectedAndroidTest: that uninstalls the app afterwards, and the pushed
 * models with it.
 */
@RunWith(AndroidJUnit4::class)
class EmbedderAndroidTest {

    @Test
    fun embeddingGemmaMatchesMeaningAcrossLanguagesOnThisPhonesAccelerator() = runBlocking<Unit> {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val files = graph.modelFiles
        assumeTrue("no EmbeddingGemma text bundle in ${files.dir}", files.status().embedderPresent)
        val embedder = graph.createEmbedder(useFake = false)
        try {
            embedder.warm()
            Log.i(TAG, "embedder: ${embedder.description}")

            val query = embedder.embed("Where are you from?", EmbedRole.QUERY)
            val (en, zh, unrelated) = embedder.embed(listOf("Which country do you come from?", "你是哪里人？", "I'd like a large coffee, please."))
            val sEn = Vectors.cosine(query, en)
            val sZh = Vectors.cosine(query, zh)
            val sUnrelated = Vectors.cosine(query, unrelated)
            Log.i(TAG, "cosine to 'Where are you from?': en paraphrase %.3f · zh paraphrase %.3f · unrelated %.3f".format(sEn, sZh, sUnrelated))
            assertTrue("en paraphrase $sEn should beat unrelated $sUnrelated", sEn > sUnrelated)
            // Character trigrams share nothing with Han script: the hashing fallback fails here.
            assertTrue("zh paraphrase $sZh should beat unrelated $sUnrelated", sZh > sUnrelated)

            // A turn embeds one query; retrieval's budget is 100 ms.
            val ms = List(20) { measureNanoTime { embedder.embed("Is this your first time in Beijing?", EmbedRole.QUERY) } / 1e6 }.sorted()
            Log.i(TAG, "query embedding, 20 runs: median %.1f ms, max %.1f ms".format(ms[ms.size / 2], ms.last()))

            files.embedderSoc?.let { soc ->
                assertTrue("expected ${soc.name} to win, got: ${embedder.description}", embedder.description.contains(soc.nameWithoutExtension))
            }
        } finally {
            embedder.close()
        }
    }

    private companion object {
        const val TAG = "EmbedderAndroidTest"
    }
}

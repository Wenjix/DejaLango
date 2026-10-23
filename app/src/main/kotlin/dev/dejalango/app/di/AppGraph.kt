package dev.dejalango.app.di

import android.content.Context
import android.util.Log
import dev.dejalango.app.platform.audio.MicAudioSource
import dev.dejalango.app.platform.audio.WavFixtureAudioSource
import dev.dejalango.app.platform.config.ConfigStore
import dev.dejalango.app.platform.store.DejaLangoDb
import dev.dejalango.app.platform.store.RoomExemplarStore
import dev.dejalango.app.platform.trace.TraceWriter
import dev.dejalango.app.platform.tts.AndroidVoiceOutput
import dev.dejalango.app.platform.vad.SileroVad
import dev.dejalango.engine.audio.AudioSource
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.hud.VoiceOutput
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.prompts.PromptSet
import dev.dejalango.engine.turn.EnergyVad
import dev.dejalango.engine.turn.VoiceActivityDetector
import dev.dejalango.engine.voice.HashingEmbedder
import dev.dejalango.engine.voice.TextEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual DI: one object, lazy singletons, constructor injection. Debug/fake variants are chosen by
 * settings, not by build type, so the same APK can flip between the fake model and Gemma at the bench.
 */
class AppGraph(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore: ConfigStore by lazy { ConfigStore(context, appScope) }
    val db: DejaLangoDb by lazy { DejaLangoDb.open(context) }
    val exemplarStore: RoomExemplarStore by lazy { RoomExemplarStore(db) }
    val traceWriter: TraceWriter by lazy { TraceWriter(context) }

    /** Kotlin defaults, overridden by any assets/prompts/<name>.txt present. */
    val promptSet: PromptSet by lazy {
        val overrides = HashMap<String, String>()
        for (name in PromptSet.DEFAULT.names) {
            runCatching { context.assets.open("prompts/$name.txt").use { it.readBytes().toString(Charsets.UTF_8) } }
                .onSuccess { overrides[name] = it }
        }
        if (overrides.isNotEmpty()) Log.i(TAG, "prompt overrides from assets: ${overrides.keys}")
        PromptSet.DEFAULT.withOverrides(overrides)
    }

    fun createVad(): VoiceActivityDetector = try {
        SileroVad(context.assets.open("silero_vad.onnx").use { it.readBytes() })
    } catch (t: Throwable) {
        Log.e(TAG, "Silero VAD unavailable (${t.message}); using energy VAD")
        EnergyVad()
    }

    fun createAudioSource(replayFixture: String?): AudioSource {
        if (replayFixture != null) {
            val bytes = runCatching { context.assets.open("fixtures/$replayFixture").use { it.readBytes() } }
                .recoverCatching { File(replayFixture).readBytes() }
                .getOrNull()
            if (bytes != null) return WavFixtureAudioSource(bytes, paced = true, loop = false)
            Log.w(TAG, "fixture $replayFixture not found; using the microphone")
        }
        return MicAudioSource()
    }

    fun createSpeechModel(cfg: PipelineConfig, useFake: Boolean): SpeechLanguageModel =
        FakeSpeechModel(l1 = cfg.l1.code, l2 = cfg.l2.code)

    fun createEmbedder(useFake: Boolean): TextEmbedder = HashingEmbedder()

    fun createVoiceOutput(): VoiceOutput = runCatching<VoiceOutput> { AndroidVoiceOutput(context) }.getOrDefault(VoiceOutput.None)

    private companion object {
        const val TAG = "AppGraph"
    }
}

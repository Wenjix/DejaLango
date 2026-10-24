package dev.dejalango.app.di

import android.content.Context
import android.util.Log
import dev.dejalango.app.BuildConfig
import dev.dejalango.app.platform.ModelFiles
import dev.dejalango.app.platform.audio.MicAudioSource
import dev.dejalango.app.platform.audio.WavFixtureAudioSource
import dev.dejalango.app.platform.config.ConfigStore
import dev.dejalango.app.platform.store.DejaLangoDb
import dev.dejalango.app.platform.store.RoomExemplarStore
import dev.dejalango.app.platform.trace.TraceWriter
import dev.dejalango.app.platform.tts.AndroidVoiceOutput
import dev.dejalango.app.platform.vad.SileroVad
import dev.dejalango.engine.audio.AudioSource
import dev.dejalango.engine.config.AudioBackend
import dev.dejalango.engine.config.Backend
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
    val modelFiles: ModelFiles by lazy { ModelFiles(context) }
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

    /** The real adapter is loaded by name so the default build (without -PwithLiteRtLm) never references it. */
    fun createSpeechModel(cfg: PipelineConfig, useFake: Boolean): SpeechLanguageModel {
        val status = modelFiles.status()
        if (!useFake && BuildConfig.WITH_LITERTLM && status.gemmaPresent) {
            try {
                val cls = Class.forName("dev.dejalango.app.platform.llm.litertlm.LiteRtLmModel")
                return cls.getConstructor(Context::class.java, ModelFiles::class.java, Backend::class.java, AudioBackend::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .newInstance(context, modelFiles, cfg.backend, cfg.audioBackend, 2048, true) as SpeechLanguageModel
            } catch (t: Throwable) {
                throw IllegalStateException("The local speech model couldn't start. Check the model setup and retry.", t)
            }
        } else if (!useFake) {
            throw IllegalStateException(if (BuildConfig.DEBUG) "The local speech model isn't available. Open the labelled walkthrough, or finish model setup in the developer bench." else "The language model isn't available on this phone. Finish local model setup, then start listening again.")
        }
        return FakeSpeechModel(l1 = cfg.l1.code, l2 = cfg.l2.code)
    }

    fun createEmbedder(useFake: Boolean): TextEmbedder {
        if (!useFake && BuildConfig.WITH_LITERTLM && modelFiles.status().embedderPresent) {
            try {
                val cls = Class.forName("dev.dejalango.app.platform.llm.litertlm.LiteRtLmEmbedder")
                return cls.getConstructor(Context::class.java, ModelFiles::class.java).newInstance(context, modelFiles) as TextEmbedder
            } catch (t: Throwable) {
                Log.e(TAG, "LiteRT-LM embedder failed to load; falling back to hashing embedder", t)
            }
        }
        return HashingEmbedder()
    }

    fun createVoiceOutput(): VoiceOutput = runCatching<VoiceOutput> { AndroidVoiceOutput(context) }.getOrDefault(VoiceOutput.None)

    private companion object {
        const val TAG = "AppGraph"
    }
}

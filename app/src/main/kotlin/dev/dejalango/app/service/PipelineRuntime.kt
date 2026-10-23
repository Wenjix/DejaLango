package dev.dejalango.app.service

import android.content.Context
import android.util.Log
import dev.dejalango.app.di.AppGraph
import dev.dejalango.app.hud.HudServer
import dev.dejalango.engine.audio.AudioSource
import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.RingBuffer
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.ConversationState
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.pipeline.TurnProcessor
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.turn.TurnPolicy
import dev.dejalango.engine.turn.TurnSegmenter
import dev.dejalango.engine.turn.VoiceActivityDetector
import dev.dejalango.engine.turn.segmentTurns
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.Retriever
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.TextEmbedder
import dev.dejalango.engine.voice.VoiceCard
import dev.dejalango.engine.voice.VoiceCardBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Wires the engine to the device: audio source → ring/VAD/segmenter → TurnProcessor → HudState,
 * plus the web HUD server, trace CSV, exemplar learning and the voice-card flows. Owned by ListenService.
 *
 * Threads: one for audio (capture + VAD + segmentation), one for the model (not reentrant). HudState is a
 * StateFlow; renderers collect wherever they like.
 */
class PipelineRuntime(private val context: Context, private val graph: AppGraph, private val serviceScope: CoroutineScope) {

    private val llmDispatcher = Executors.newSingleThreadExecutor { Thread(it, "dejalango-llm") }.asCoroutineDispatcher()
    private val audioDispatcher = Executors.newSingleThreadExecutor { Thread(it, "dejalango-audio") }.asCoroutineDispatcher()
    private val llmScope = serviceScope + llmDispatcher

    val config: StateFlow<PipelineConfig> get() = graph.configStore.config
    val conversation = ConversationState()

    private val ring = RingBuffer()
    private val vad: VoiceActivityDetector = graph.createVad()
    private val segmenter = TurnSegmenter(TurnPolicy.forMode(config.value.mode), ring)
    private val voiceOutput = graph.createVoiceOutput()

    // Switching fake ↔ real (settings) restarts the service; these are fixed for the runtime's life.
    private val model: SpeechLanguageModel = graph.createSpeechModel(config.value, graph.configStore.settings.value.useFakeModel)
    private val embedder: TextEmbedder = graph.createEmbedder(graph.configStore.settings.value.useFakeModel)
    private val retriever = Retriever(graph.exemplarStore, embedder)

    val processor = TurnProcessor(model, retriever, conversation, PromptBuilder(graph.promptSet), config, llmScope)
    val hud: StateFlow<HudState> get() = processor.hud

    private var hudServer: HudServer? = null
    private val _hudUrl = MutableStateFlow<String?>(null)
    val hudUrl: StateFlow<String?> = _hudUrl.asStateFlow()

    private var audioJob: Job? = null
    private var source: AudioSource? = null
    private val jobs = ArrayList<Job>()

    @Volatile
    var isListening: Boolean = false
        private set

    /** What the bench shows under the model line: SoC, which bundles are on the device, the push command. */
    val modelStatusLine: String
        get() = graph.modelFiles.status().let { s ->
            "soc=${s.socModel.ifEmpty { "?" }} · generic=${s.gemmaGeneric ?: "missing"} · soc bundle=${s.gemmaSoc ?: "missing"} · embedder=${s.embedder ?: "missing"}"
        }

    suspend fun start() {
        // 1. Warm the model with the persisted voice card (quick cards are never persisted).
        val card = runCatching { graph.exemplarStore.loadVoiceCard() }.getOrNull()
        withContext(llmDispatcher) { processor.warm(card) }

        // 2. Side channels: traces → CSV; used replies → exemplar store (L2, PICKED_REPLY) + optional TTS; config → policy.
        jobs += serviceScope.launch { processor.traces.collect { t -> runCatching { graph.traceWriter.append(t) }; Log.i(TAG, t.summary()) } }
        jobs += serviceScope.launch {
            processor.usedReplies.collect { text ->
                val cfg = config.value
                runCatching { graph.exemplarStore.add(text, cfg.l2.code, Source.PICKED_REPLY, embedder.embed(text)) }
                if (cfg.speakReplies) voiceOutput.say(text, cfg.l2.code)
            }
        }
        jobs += serviceScope.launch {
            config.drop(1).collect { cfg ->
                segmenter.policy = TurnPolicy.forMode(cfg.mode)
                processor.onConfigChanged(cfg)
            }
        }

        // 3. Web HUD.
        startHudServer()

        // 4. Audio.
        startAudio()
    }

    private fun startHudServer() {
        val s = graph.configStore.settings.value
        try {
            val server = HudServer(context.assets, if (s.hudOnLan) null else "127.0.0.1", s.hudPort, hud, ::onAction, serviceScope)
            server.startServer()
            hudServer = server
            _hudUrl.value = server.url
        } catch (t: Throwable) {
            Log.e(TAG, "HUD server failed to start: ${t.message}")
            _hudUrl.value = null
        }
    }

    fun startAudio() {
        stopAudio()
        val src = graph.createAudioSource(graph.configStore.settings.value.replayFixture)
        source = src
        vad.reset()
        segmenter.reset()
        audioJob = serviceScope.launch(audioDispatcher) {
            try {
                src.start()
                isListening = true
                processor.run(segmentTurns(src.frames, vad, ring, segmenter))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "audio pipeline stopped: ${t.message}", t)
            } finally {
                isListening = false
            }
        }
    }

    fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        source?.stop()
        source = null
        isListening = false
    }

    fun onAction(a: HudAction) {
        when (a) {
            is HudAction.SetMode -> graph.configStore.update { it.copy(mode = a.mode) }
            HudAction.CutNow -> serviceScope.launch(audioDispatcher) { segmenter.cutNow(ring.endMs ?: 0L).forEach(processor::onEvent) }
            else -> processor.onAction(a)
        }
    }

    /** Judge flow: the last [seconds] of audio in the ring buffer → QUICK card → re-warm. Not persisted. */
    suspend fun buildQuickCardFromRecentAudio(seconds: Int = 60): VoiceCard {
        val end = ring.endMs ?: return VoiceCard.none(config.value.l1.code, config.value.l2.code)
        val chunks = ArrayList<AudioTurn>()
        var to = end
        var left = seconds * 1000L
        while (left > 0) {
            val len = minOf(left, 28_000L)
            chunks += ring.turn(to - len, to)
            to -= len
            left -= len
        }
        val r = withContext(llmDispatcher) { VoiceCardBuilder(model, PromptBuilder(graph.promptSet)).quickFromAudio(config.value, chunks.reversed()) }
        withContext(llmDispatcher) { processor.warm(r.card) }
        return r.card
    }

    /** Owner flow: an hour of transcript sentences → FULL card (persisted) + every sentence embedded into the store. */
    suspend fun buildFullCard(sentences: List<String>): VoiceCard {
        val cfg = config.value
        val r = withContext(llmDispatcher) { VoiceCardBuilder(model, PromptBuilder(graph.promptSet)).fromSentences(cfg, sentences, Confidence.FULL) }
        graph.exemplarStore.saveVoiceCard(r.card)
        val vectors = embedder.embed(r.sentences)
        r.sentences.zip(vectors).forEach { (s, v) -> graph.exemplarStore.add(s, cfg.l1.code, Source.TRANSCRIPT, v) }
        withContext(llmDispatcher) { processor.warm(r.card) }
        return r.card
    }

    suspend fun wipe() {
        graph.exemplarStore.wipe()
        conversation.clear()
        withContext(llmDispatcher) { processor.warm(null) }
    }

    fun stop() {
        stopAudio()
        jobs.forEach { it.cancel() }
        jobs.clear()
        hudServer?.stop()
        hudServer = null
        _hudUrl.value = null
        processor.cancel()
        runCatching { model.close() }
        voiceOutput.close()
        llmDispatcher.close()
        audioDispatcher.close()
    }

    private companion object {
        const val TAG = "PipelineRuntime"
    }
}

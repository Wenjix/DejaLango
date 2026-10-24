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
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.pipeline.TurnProcessor
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.turn.EndReason
import dev.dejalango.engine.turn.TurnEvent
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
import kotlinx.coroutines.Dispatchers
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

    // A minute, so the quick card really gets 60 s; turns stay under Gemma's 30 s audio cap via TurnPolicy.maxTurnMs.
    private val ring = RingBuffer(seconds = 60)
    private val vad: VoiceActivityDetector = graph.createVad()
    private val segmenter = TurnSegmenter(TurnPolicy.forMode(config.value.mode), ring)
    private val voiceOutput = graph.createVoiceOutput()

    // Switching fake ↔ real (settings) restarts the service; these are fixed for the runtime's life.
    private val model: SpeechLanguageModel = graph.createSpeechModel(config.value, graph.configStore.settings.value.useFakeModel)
    private val embedder: TextEmbedder = graph.createEmbedder(graph.configStore.settings.value.useFakeModel)
    private val retriever = Retriever(graph.exemplarStore, embedder)

    /**
     * Whether voice cards and exemplars built here are saved. Not with the fake model (also AppGraph's silent fallback):
     * its canned card and transcripts would reach the store that real-model sessions load.
     */
    val savesVoice: Boolean = model !is FakeSpeechModel

    val processor = TurnProcessor(model, retriever, conversation, PromptBuilder(graph.promptSet), config, llmScope)
    val hud: StateFlow<HudState> get() = processor.hud

    private var hudServer: HudServer? = null
    private val _hudUrl = MutableStateFlow<String?>(null)
    val hudUrl: StateFlow<String?> = _hudUrl.asStateFlow()

    /** What the exemplar store holds: every indexed sentence, and my L1 ones, the pool behind "because you say". */
    data class ExemplarCounts(val total: Int = 0, val l1: Int = 0)

    private val _exemplarCounts = MutableStateFlow(ExemplarCounts())
    val exemplarCounts: StateFlow<ExemplarCounts> = _exemplarCounts.asStateFlow()

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
            HudAction.CutNow -> serviceScope.launch(audioDispatcher) {
                val endMs = ring.endMs ?: 0L
                val events = segmenter.cutNow(endMs)
                if (events.isNotEmpty()) {
                    events.forEach(processor::onEvent)
                } else {
                    // If VAD was not currently latched in Speech when the user tapped Cut, slice the recent
                    // audio window from the ring buffer so Cut still forces a manual turn immediately.
                    val fromMs = maxOf(ring.startMs ?: 0L, endMs - 3_000L)
                    val turn = if (endMs > fromMs) ring.turn(fromMs, endMs) else AudioTurn(FloatArray(16_000), 0L, 1_000L, 16_000)
                    processor.onEvent(TurnEvent.TurnEnded(turn, EndReason.MANUAL))
                }
            }
            else -> processor.onAction(a)
        }
    }

    /**
     * Judge flow: the last [seconds] of audio in the ring buffer → QUICK card (in memory, not persisted) plus the L1
     * sentences heard, indexed as exemplars, so both shape the very next replies.
     */
    suspend fun buildQuickCardFromRecentAudio(seconds: Int = 60): VoiceCard {
        val cfg = config.value
        val end = ring.endMs ?: return VoiceCard.none(cfg.l1.code, cfg.l2.code)
        val start = maxOf(ring.startMs ?: end, end - seconds * 1000L)
        val chunks = ArrayList<AudioTurn>()
        var to = end
        while (to - start >= MIN_CHUNK_MS) { // a sliver is too short to transcribe
            val from = maxOf(start, to - MAX_CHUNK_MS)
            chunks += ring.turn(from, to)
            to = from
        }
        val r = withContext(llmDispatcher) { VoiceCardBuilder(processor.ownerModel, PromptBuilder(graph.promptSet)).quickFromAudio(cfg, chunks.reversed()) }
        if (savesVoice) index(r.sentences, cfg.l1.code)
        // Nothing heard in my language: keep the card I have rather than fall back to none.
        if (r.card.confidence != Confidence.NONE) withContext(llmDispatcher) { processor.warm(r.card) } else processor.prepareNext()
        return r.card
    }

    /** Owner flow: an hour of transcript sentences → FULL card (persisted) + every sentence embedded into the store. */
    suspend fun buildFullCard(sentences: List<String>): VoiceCard {
        val cfg = config.value
        val r = withContext(llmDispatcher) { VoiceCardBuilder(processor.ownerModel, PromptBuilder(graph.promptSet)).fromSentences(cfg, sentences, Confidence.FULL) }
        if (savesVoice) {
            graph.exemplarStore.saveVoiceCard(r.card)
            index(r.sentences, cfg.l1.code)
        }
        // An unparseable card comes back NONE (and is not saved): keep the card I have.
        if (r.card.confidence != Confidence.NONE) withContext(llmDispatcher) { processor.warm(r.card) } else processor.prepareNext()
        return r.card
    }

    /** Bench flow: the bundled persona corpus `assets/voice_seed_<l1>.txt` through [buildFullCard], as if it were my transcript. */
    suspend fun seedDefaultVoiceCard(): VoiceCard {
        val asset = "voice_seed_${config.value.l1.code}.txt"
        val sentences = withContext(Dispatchers.IO) {
            context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }
        return buildFullCard(sentences)
    }

    suspend fun wipe() {
        graph.exemplarStore.wipe()
        conversation.clear()
        withContext(llmDispatcher) { processor.warm(null) }
        refreshExemplarCounts()
    }

    /** Embeds [sentences] as mine ([Source.TRANSCRIPT]) in [lang], skipping ones already indexed so re-seeding adds nothing twice. */
    private suspend fun index(sentences: List<String>, lang: String) {
        val known = graph.exemplarStore.all(lang).mapTo(HashSet()) { it.text }
        val fresh = sentences.map { it.trim() }.filter { it.isNotEmpty() && known.add(it) }
        if (fresh.isNotEmpty()) {
            val vectors = withContext(llmDispatcher) { embedder.embed(fresh) } // the thread retrieval embeds on, never main
            fresh.zip(vectors).forEach { (s, v) -> graph.exemplarStore.add(s, lang, Source.TRANSCRIPT, v) }
        }
        refreshExemplarCounts()
    }

    private suspend fun refreshExemplarCounts() {
        runCatching { ExemplarCounts(total = graph.exemplarStore.count(), l1 = graph.exemplarStore.all(config.value.l1.code).size) }
            .onSuccess { _exemplarCounts.value = it }
            .onFailure { Log.w(TAG, "exemplar count failed: ${it.message}") }
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
        const val MAX_CHUNK_MS = 28_000L // under Gemma's 30 s audio cap
        const val MIN_CHUNK_MS = 1_000L
    }
}

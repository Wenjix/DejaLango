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
import dev.dejalango.engine.pipeline.CompanionProcessor
import dev.dejalango.engine.playbook.Playbook
import dev.dejalango.engine.playbook.PlaybookIndex
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.turn.EndReason
import dev.dejalango.engine.turn.TurnEvent
import dev.dejalango.engine.turn.TurnPolicy
import dev.dejalango.engine.turn.TurnSegmenter
import dev.dejalango.engine.turn.CompanionInput
import dev.dejalango.engine.turn.VoiceActivityDetector
import dev.dejalango.engine.turn.segmentTurns
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.Retriever
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.TextEmbedder
import dev.dejalango.engine.voice.VoiceCard
import dev.dejalango.engine.voice.VoiceCardBuilder
import dev.dejalango.engine.voice.HashingEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the engine to the device: audio source → ring/VAD → CompanionInput → CompanionProcessor → HudState,
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
    private val input = CompanionInput()
    private val ring get() = input.ring
    private val vadDelegate = lazy { graph.createVad() }
    private val vad: VoiceActivityDetector by vadDelegate

    // Switching fake ↔ real (settings) restarts the service; these are fixed for the runtime's life.
    private val model: SpeechLanguageModel = graph.createSpeechModel(config.value, graph.configStore.settings.value.useFakeModel)
    private val embedder: TextEmbedder = graph.createEmbedder(graph.configStore.settings.value.useFakeModel)
    private val voiceOutput = graph.createVoiceOutput()
    private val retriever = if (embedder is HashingEmbedder) null else Retriever(graph.exemplarStore, embedder)

    /**
     * Whether voice cards and exemplars built here are saved. Not with the fake model (also AppGraph's silent fallback):
     * its canned card and transcripts would reach the store that real-model sessions load.
     */
    val savesVoice: Boolean = model !is FakeSpeechModel && embedder !is HashingEmbedder

    /** The playbook's lines, embedded after start: null until they are, and whenever the playbook is unavailable. */
    @Volatile
    var playbookIndex: PlaybookIndex? = null
        private set

    val processor: CompanionProcessor = CompanionProcessor(
        model, config, llmScope, retriever, PromptBuilder(graph.promptSet),
        hintOutcome = { turn, pause, ready -> serviceScope.launch(audioDispatcher) { input.hintResult(turn, pause, ready).forEach(processorInput) } },
        audioSlice = { from, to -> withContext(audioDispatcher) { input.slice(from, to) } },
        playbook = { playbookIndex },
    )
    private val processorInput: (dev.dejalango.engine.turn.CaptureEvent) -> Unit get() = processor::onInput
    val hud: StateFlow<HudState> get() = processor.hud

    private var hudServer: HudServer? = null
    private val _hudUrl = MutableStateFlow<String?>(null)
    val hudUrl: StateFlow<String?> = _hudUrl.asStateFlow()

    /** What the exemplar store holds: every indexed sentence, and my L1 ones, the pool behind "because you say". */
    data class ExemplarCounts(val total: Int = 0, val l1: Int = 0)

    private val _exemplarCounts = MutableStateFlow(ExemplarCounts())
    val exemplarCounts: StateFlow<ExemplarCounts> = _exemplarCounts.asStateFlow()

    /** A scene of the loaded playbook, as the bench offers it: [id] is what the config stores as its playbook scene. */
    data class PlaybookScene(val id: String, val title: String)

    private val _playbookStatus = MutableStateFlow("playbook: off")
    /** The bench's playbook line: off, loading, indexing n/N, ready, or unavailable and why. */
    val playbookStatus: StateFlow<String> = _playbookStatus.asStateFlow()

    private val _playbookScenes = MutableStateFlow<List<PlaybookScene>>(emptyList())
    /** The loaded playbook's scenes, for the bench to choose from; empty while it is unavailable. */
    val playbookScenes: StateFlow<List<PlaybookScene>> = _playbookScenes.asStateFlow()

    private var audioJob: Job? = null
    private var source: AudioSource? = null
    private val jobs = ArrayList<Job>()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    @Volatile
    var isListening: Boolean = false
        private set

    /** What the bench shows under the model line: SoC, which Gemma bundles are on the device, what the embedder runs on. */
    val modelStatusLine: String
        get() = graph.modelFiles.status().let { s ->
            "soc=${s.socModel.ifEmpty { "?" }} · generic=${s.gemmaGeneric ?: "missing"} · soc bundle=${s.gemmaSoc ?: "missing"} · embedder=${embedder.description}"
        }

    suspend fun start() = processor.ownerOperation {
        // 1. Warm the model with the persisted voice card (quick cards are never persisted).
        val card = runCatching { graph.exemplarStore.loadVoiceCard(config.value.l1.code) }.getOrNull()
        withContext(llmDispatcher) { processor.warm(card) }
        if (!hud.value.modelReady) return@ownerOperation
        withContext(llmDispatcher) { embedder.warm() } // its accelerator build, or what it fell back to: the bench says which
        jobs += llmScope.launch { loadPlaybook(config.value) } // in batches between turns; until it is ready, Stage B writes every reply
        refreshExemplarCounts()

        // 2. Side channels: traces → CSV; used replies → exemplar store (L2, PICKED_REPLY) + optional TTS; config → policy.
        jobs += serviceScope.launch { processor.traces.collect { t -> runCatching { graph.traceWriter.append(t) }; Log.i(TAG, t.summary()) } }
        jobs += serviceScope.launch {
            processor.usedReplies.collect { used ->
                val cfg = config.value
                if (cfg.speakReplies) voiceOutput.say(used.text, used.language)
            }
        }
        jobs += serviceScope.launch {
            config.collect { cfg ->
                withContext(audioDispatcher) { processor.onConfigChanged(cfg) }
            }
        }
        jobs += serviceScope.launch {
            hud.map { it.pair }.distinctUntilChanged().collect { pair ->
                val stored = withContext(Dispatchers.IO) { graph.exemplarStore.loadVoiceCard(pair.me.code) }
                processor.useVoiceCard(pair, stored)
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
        if (isListening || audioJob?.isActive == true || !hud.value.modelReady) return
        val src = graph.createAudioSource(graph.configStore.settings.value.replayFixture)
        source = src
        audioJob = serviceScope.launch(audioDispatcher) {
            try {
                vad.reset()
                input.reset().forEach(processorInput)
                src.start()
                isListening = true
                processor.listening(true)
                var dropped = 0L
                src.frames.collect { frame ->
                    input.frame(frame, vad.probability(frame.pcm)).forEach(processorInput)
                    if (ring.droppedSamples != dropped) {
                        dropped = ring.droppedSamples
                        Log.w(TAG, "Audio gaps: missingSamples=$dropped")
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "audio pipeline stopped: ${t.message}", t)
                processor.inputError("Microphone stopped. Check microphone access, then try listening again.")
            } finally {
                src.stop()
                if (source === src) source = null
                isListening = false
                processor.listening(false)
            }
        }
    }

    fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        source?.stop()
        source = null
        isListening = false
        processor.listening(false)
    }

    fun onAction(a: HudAction) {
        serviceScope.launch(audioDispatcher) {
            when (a) {
                HudAction.Talk -> if (isListening) input.talk().forEach(processorInput)
                HudAction.Done -> if (isListening) input.done().forEach(processorInput)
                HudAction.CutNow -> if (isListening) input.operatorCut().forEach(processorInput)
                HudAction.Clear -> { input.reset().forEach(processorInput); processor.clear() }
                is HudAction.SetMode -> Unit
                else -> processor.onAction(a)
            }
        }
    }

    /**
     * Judge flow: the last [seconds] of audio in the ring buffer → QUICK card (in memory, not persisted) plus the L1
     * sentences heard, indexed as exemplars, so both shape the very next replies.
     */
    suspend fun buildQuickCardFromRecentAudio(seconds: Int = 60): VoiceCard = processor.ownerOperation { ownerModel ->
        val cfg = config.value
        val chunks = withContext(audioDispatcher) {
            val end = ring.endMs ?: return@withContext emptyList<AudioTurn>()
            val start = maxOf(ring.startMs ?: end, end - seconds * 1000L)
            val result = ArrayList<AudioTurn>()
            var to = end
            while (to - start >= MIN_CHUNK_MS) {
                val from = maxOf(start, to - MAX_CHUNK_MS)
                result += ring.turn(from, to)
                to = from
            }
            result.reversed()
        }
        val r = withContext(llmDispatcher) { VoiceCardBuilder(ownerModel, PromptBuilder(graph.promptSet)).quickFromAudio(cfg, chunks) }
        if (savesVoice) index(r.sentences, cfg.l1.code)
        // Nothing heard in my language: keep the card I have rather than fall back to none.
        if (r.card.confidence != Confidence.NONE) processor.useVoiceCard(cfg.pair, r.card)
        processor.prepareNext()
        r.card
    }

    /** Owner flow: an hour of transcript sentences → FULL card (persisted) + every sentence embedded into the store. */
    suspend fun buildFullCard(sentences: List<String>): VoiceCard = processor.ownerOperation { ownerModel ->
        val cfg = config.value
        val r = withContext(llmDispatcher) { VoiceCardBuilder(ownerModel, PromptBuilder(graph.promptSet)).fromSentences(cfg, sentences, Confidence.FULL) }
        if (savesVoice) {
            graph.exemplarStore.saveVoiceCard(r.card)
            index(r.sentences, cfg.l1.code)
        }
        // An unparseable card comes back NONE (and is not saved): keep the card I have.
        if (r.card.confidence != Confidence.NONE) processor.useVoiceCard(cfg.pair, r.card)
        processor.prepareNext()
        r.card
    }

    /** Bench flow: the bundled persona corpus `assets/voice_seed_<l1>.txt` through [buildFullCard], as if it were my transcript. */
    suspend fun seedDefaultVoiceCard(): VoiceCard {
        val language = config.value.l1
        val files = context.assets.list("").orEmpty().toSet()
        val asset = listOf("voice_seed_${language.tag}.txt", "voice_seed_${language.code}.txt").firstOrNull { it in files }
            ?: error("No bundled sample for ${language.label}. Add your own words in Your voice instead.")
        val sentences = withContext(Dispatchers.IO) {
            context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }
        return buildFullCard(sentences)
    }

    suspend fun wipe() = processor.ownerOperation {
        graph.exemplarStore.wipe()
        conversation.clear()
        processor.clear()
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

    /**
     * Reads `assets/playbook/<L2 tag>.json` and, once a scene is chosen, embeds its lines into [playbookIndex]: each
     * batch under the model lock and between turns, so it never overlaps a generation. Any failure leaves the playbook
     * unavailable, and every reply is Stage B's, as before.
     */
    private suspend fun loadPlaybook(cfg: PipelineConfig) {
        val tag = cfg.l2.tag
        try {
            _playbookStatus.value = "playbook: loading"
            val playbook = withContext(Dispatchers.IO) {
                val json = try {
                    context.assets.open("playbook/$tag.json").use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: FileNotFoundException) {
                    null
                }
                json?.let { Playbook.parse(it) }
            } ?: return playbookUnavailable("no playbook for $tag")
            // CompanionProcessor skips a playbook for another pair: say so here rather than look ready.
            if (playbook.l1 != cfg.l1.tag || playbook.l2 != tag) return playbookUnavailable("for ${playbook.l1}→${playbook.l2}, not ${cfg.l1.tag}→$tag")
            // Hashing vectors carry no meaning, so every hit would be a guess.
            if (embedder is HashingEmbedder || embedder.description.contains("hashing", ignoreCase = true)) return playbookUnavailable("embedder is ${embedder.description}")
            _playbookScenes.value = playbook.scenes.map { PlaybookScene(it.id, it.title) }
            if (config.value.playbookScene.isBlank()) {
                _playbookStatus.value = "playbook: off · ${playbook.scenes.size} scenes"
                config.first { it.playbookScene.isNotBlank() } // index only once a scene is chosen: off does no model work
            }
            val t0 = System.nanoTime()
            val total = playbook.scenes.sumOf { scene -> scene.moments.sumOf { it.local.size + it.replies.size + it.others.size } }
            var done = 0
            _playbookStatus.value = "playbook: indexing 0/$total"
            val index = PlaybookIndex.build(playbook, embedder, PLAYBOOK_BATCH) { batch ->
                hud.first { !it.processing } // no batch starts during a turn, so a turn waits for one batch at most
                processor.exclusive {
                    if (closing.get()) throw CancellationException("Runtime stopped") // stop() is about to close the embedder
                    batch()
                }
                done = minOf(done + PLAYBOOK_BATCH, total)
                _playbookStatus.value = "playbook: indexing $done/$total"
            }
            // A fallback while indexing leaves vectors that no query compares with: PlaybookIndex.match would miss every line.
            if (index.description != embedder.description || index.description.contains("hashing", ignoreCase = true)) {
                return playbookUnavailable("embedder changed while indexing: ${index.description}")
            }
            playbookIndex = index
            val ms = (System.nanoTime() - t0) / 1_000_000
            _playbookStatus.value = "playbook: ready · ${index.size} lines · ${"%.1f".format(Locale.ROOT, ms / 1000.0)} s · ${index.description}"
            Log.i(TAG, "playbook $tag: ${index.size} lines indexed in $ms ms by ${index.description}")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            playbookUnavailable(t.message?.removePrefix("playbook: ") ?: t.javaClass.simpleName, t)
        }
    }

    private fun playbookUnavailable(reason: String, error: Throwable? = null) {
        playbookIndex = null
        _playbookScenes.value = emptyList()
        _playbookStatus.value = "playbook: unavailable ($reason)"
        Log.w(TAG, "playbook unavailable: $reason", error)
    }

    private suspend fun refreshExemplarCounts() {
        runCatching { ExemplarCounts(total = graph.exemplarStore.count(), l1 = graph.exemplarStore.all(config.value.l1.code).size) }
            .onSuccess { _exemplarCounts.value = it }
            .onFailure { Log.w(TAG, "exemplar count failed: ${it.message}") }
    }

    fun stop() {
        if (!closing.compareAndSet(false, true)) return
        val capture = audioJob
        stopAudio()
        jobs.forEach { it.cancel() }
        jobs.clear()
        hudServer?.stop()
        hudServer = null
        _hudUrl.value = null
        processor.shutdown()
        // This scope outlives the service's canceled scope. An idle executor can still own a suspended SDK
        // flow; wait for full owner operations and the model lock before closing either engine/dispatcher.
        CoroutineScope(llmDispatcher).launch {
            try {
                capture?.join()
                processor.awaitIdle()
                withContext(audioDispatcher) { if (vadDelegate.isInitialized()) runCatching { vad.close() } }
                runCatching { model.close() }
                runCatching { embedder.close() }
                runCatching { voiceOutput.close() }
            } finally {
                llmDispatcher.close()
                audioDispatcher.close()
                closed.complete(Unit)
            }
        }
    }

    suspend fun awaitClosed() { closed.await() }

    private companion object {
        const val TAG = "PipelineRuntime"
        const val MAX_CHUNK_MS = 28_000L // under Gemma's 30 s audio cap
        const val MIN_CHUNK_MS = 1_000L
        const val PLAYBOOK_BATCH = 16 // lines per embedding call: the longest a turn waits for the model lock while indexing
    }
}

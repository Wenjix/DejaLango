package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.conversation.ConversationState
import dev.dejalango.engine.conversation.ReplyMatcher
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.ExemplarView
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Line
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.hud.Reply
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.Generated
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.generateText
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.prompts.Schemas
import dev.dejalango.engine.trace.TurnTrace
import dev.dejalango.engine.turn.EndReason
import dev.dejalango.engine.turn.TurnEvent
import dev.dejalango.engine.voice.Exemplar
import dev.dejalango.engine.voice.RetrievedExemplar
import dev.dejalango.engine.voice.Retriever
import dev.dejalango.engine.voice.VoiceCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns [TurnEvent]s into [HudState]. One generation at a time; the newest turn wins; a resumed
 * speaker cancels a stage-A generation in flight. Every turn produces a [TurnTrace].
 *
 * Mode-level actions the processor does not own (changing the mode, forcing a cut) are left to the
 * app; see [onAction].
 */
class TurnProcessor(
    private val model: SpeechLanguageModel,
    private val retriever: Retriever?,
    private val conversation: ConversationState,
    private val prompts: PromptBuilder,
    private val config: StateFlow<PipelineConfig>,
    private val scope: CoroutineScope,
    private val parser: TurnResultParser = TurnResultParser(),
    private val clock: Clock = Clock.system(),
    private val wordConfidenceGate: Float = 0.45f,
) {
    private enum class Stage { NONE, A, B, WORD }

    private val _hud = MutableStateFlow(HudState(phase = Phase.WARMING, mode = config.value.mode, model = model.info))
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _traces = MutableSharedFlow<TurnTrace>(extraBufferCapacity = 64)
    val traces: SharedFlow<TurnTrace> = _traces.asSharedFlow()

    /** Replies I actually used (matched from my speech or confirmed on the HUD), in L2 — the app embeds them. */
    private val _usedReplies = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val usedReplies: SharedFlow<String> = _usedReplies.asSharedFlow()

    var voiceCard: VoiceCard? = null
        private set

    private var current: Job? = null

    @Volatile
    private var stage: Stage = Stage.NONE
    private var turnCounter = 0L
    private var lastLevelMs = 0L

    /** Load the model and prefill the base prompt. Call again with a new card to rebuild the base. */
    suspend fun warm(card: VoiceCard?) {
        voiceCard = card
        _hud.update { it.copy(phase = Phase.WARMING, statusLine = "loading model", voiceCard = card, error = null) }
        try {
            model.warm(prompts.system(config.value, card))
            _hud.update { it.copy(phase = Phase.IDLE, statusLine = null, model = model.info, mode = config.value.mode) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _hud.update { it.copy(phase = Phase.ERROR, statusLine = null, error = "model failed to load: ${t.message}") }
        }
    }

    suspend fun run(events: Flow<TurnEvent>) {
        events.collect { onEvent(it) }
    }

    fun onEvent(e: TurnEvent) {
        when (e) {
            is TurnEvent.Level -> if (e.tMs - lastLevelMs >= LEVEL_THROTTLE_MS) {
                lastLevelMs = e.tMs
                _hud.update { it.copy(level = e.prob) }
            }

            is TurnEvent.SpeechStarted -> _hud.update {
                if (it.phase == Phase.THINKING || it.phase == Phase.WARMING || it.phase == Phase.ERROR) it else it.copy(phase = Phase.LISTENING)
            }

            is TurnEvent.TurnEnded -> start(e.turn, e.reason)

            is TurnEvent.SpeechResumed -> if (stage == Stage.A) cancel()
        }
    }

    fun onAction(a: HudAction) {
        when (a) {
            is HudAction.Select -> _hud.update { if (a.index in it.replies.indices) it.copy(selectedReply = a.index) else it }
            HudAction.Next -> _hud.update { it.copy(selectedReply = cycle(it.selectedReply, it.replies.size, +1)) }
            HudAction.Prev -> _hud.update { it.copy(selectedReply = cycle(it.selectedReply, it.replies.size, -1)) }
            HudAction.Confirm -> {
                val s = _hud.value
                val idx = s.selectedReply ?: return
                val r = s.replies.getOrNull(idx) ?: return
                conversation.markReplyUsed()
                _usedReplies.tryEmit(r.yours)
                _hud.update { it.copy(learnedToday = conversation.learnedToday) }
            }
            HudAction.TogglePlain -> _hud.update { it.copy(showPlain = !it.showPlain) }
            HudAction.Clear -> {
                cancel()
                _hud.update { it.copy(phase = Phase.IDLE, heard = null, replies = emptyList(), selectedReply = null, wordHint = null, wordAlternatives = emptyList(), exemplarsUsed = emptyList(), error = null) }
            }
            is HudAction.SetMode, HudAction.CutNow -> Unit // app-level: config and segmenter are owned there
        }
    }

    /** Reflect an external config change (mode, languages) on the HUD without touching the model. */
    fun onConfigChanged(cfg: PipelineConfig) {
        _hud.update { it.copy(mode = cfg.mode) }
    }

    fun cancel() {
        current?.cancel()
        current = null
    }

    private fun start(turn: AudioTurn, reason: EndReason) {
        current?.cancel()
        current = scope.launch { processTurn(turn, reason) }
    }

    private suspend fun processTurn(turn: AudioTurn, reason: EndReason) {
        val cfg = config.value
        val id = ++turnCounter
        val trace = TurnTrace(
            turnId = id, tTurnEnd = clock.nowMs(), audioMs = turn.durationMs, reason = reason.name,
            backend = model.info.backend, shape = if (cfg.mode == Mode.WORD_FINDING) "WORD" else cfg.shape.name,
        )
        _hud.update { it.copy(phase = Phase.THINKING, trace = trace.copy(), error = null) }
        try {
            when {
                cfg.mode == Mode.WORD_FINDING -> processWord(turn, cfg, trace)
                cfg.shape == PipelineShape.SINGLE -> processSingle(turn, cfg, trace)
                else -> processTwoStage(turn, cfg, trace)
            }
        } catch (ce: CancellationException) {
            trace.cancelled = true
            _hud.update { it.copy(phase = restingPhase(it), trace = trace.copy()) }
            throw ce
        } catch (t: Throwable) {
            trace.parseFailed = true
            _hud.update { it.copy(phase = restingPhase(it), error = "turn failed: ${t.message}", trace = trace.copy()) }
        } finally {
            stage = Stage.NONE
            trace.tRendered = clock.nowMs()
            _hud.update { it.copy(trace = trace.copy()) }
            _traces.tryEmit(trace.copy())
            // Idle-time prep (e.g. pre-prefilling the base prompt). Skipped when a newer turn is waiting.
            if (!trace.cancelled) runCatching { withContext(NonCancellable) { model.prepare() } }
        }
    }

    private fun schema(cfg: PipelineConfig, s: String): String? = if (cfg.structuredOutput) s else null

    // ---- two-stage ------------------------------------------------------------------------------

    private suspend fun processTwoStage(turn: AudioTurn, cfg: PipelineConfig, trace: TurnTrace) {
        stage = Stage.A
        trace.tStageAStart = clock.nowMs()
        val ctx = conversation.window(cfg.contextTurns)
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.stageA(cfg, ctx), maxTokens = 160, thinking = cfg.thinking, tag = RequestTag.STAGE_A, jsonSchema = schema(cfg, Schemas.STAGE_A)))
        if (gen == null) {
            trace.timedOut = true
            _hud.update { it.copy(phase = restingPhase(it), error = "transcription timed out") }
            return
        }
        trace.tAFirstToken = gen.firstTokenMs; trace.tAEnd = gen.doneMs
        trace.tokensA = gen.stats?.outputTokens ?: 0; trace.decodeTpsA = gen.stats?.decodeTokensPerSec

        val a = parser.parseStageA(gen.text) ?: run {
            trace.parseFailed = true
            val raw = parser.rawFallback(gen.text)
            StageAResult(Speaker.THEM, "", raw, raw)
        }
        if (a.repaired) trace.repaired = true
        if (a.heard.isEmpty()) {
            _hud.update { it.copy(phase = restingPhase(it)) }
            return
        }
        conversation.add(a.speaker, a.lang, a.heard, a.heardL1, turn.endMs)
        _hud.update { it.copy(heard = Line(a.heard, a.lang, a.heardL1), trace = trace.copy()) }

        when (cfg.mode) {
            Mode.REPLY_OPTIONS -> if (a.speaker == Speaker.THEM) {
                replyStage(cfg, trace, query = a.heardL1) { ex -> prompts.stageB(cfg, a.heard, a.heardL1, conversation.window(cfg.contextTurns), ex, voiceCard) }
            } else {
                myTurn(a)
            }
            Mode.SAY_IT_MY_WAY -> if (a.speaker == Speaker.ME || model.info.backend == "fake") {
                replyStage(cfg, trace, query = a.heardL1) { ex -> prompts.sayItMyWay(cfg, a.heardL1, ex, voiceCard) }
            } else {
                _hud.update { it.copy(phase = Phase.SHOWING, replies = emptyList(), selectedReply = null) }
            }
            Mode.WORD_FINDING -> Unit // handled by processWord
        }
    }

    /** Stage B: retrieve exemplars for [query] (L1 text) → prompt → generate → parse → show. */
    private suspend fun replyStage(cfg: PipelineConfig, trace: TurnTrace, query: String, build: (List<Exemplar>) -> String) {
        stage = Stage.B
        trace.tBStart = clock.nowMs()
        val retrieved = retrieve(query, cfg)
        trace.tRetrieved = clock.nowMs()
        val prompt = build(retrieved.map { it.exemplar })
        val gen = generateOrNull(cfg, GenerateRequest(text = prompt, maxTokens = 220, thinking = cfg.thinking, tag = RequestTag.STAGE_B, jsonSchema = schema(cfg, Schemas.STAGE_B)))
        if (gen == null) {
            trace.timedOut = true
            _hud.update { it.copy(phase = Phase.SHOWING, replies = emptyList(), selectedReply = null, error = "replies timed out") }
            return
        }
        trace.tBFirstToken = gen.firstTokenMs; trace.tBEnd = gen.doneMs
        trace.tokensB = gen.stats?.outputTokens ?: 0; trace.decodeTpsB = gen.stats?.decodeTokensPerSec
        val extracted = parser.extract(gen.text)
        if (extracted?.repaired == true) trace.repaired = true
        showReplies(extracted?.let { parser.parseReplies(it.obj) } ?: emptyList(), gen.text, cfg, retrieved, trace)
    }

    private fun showReplies(parsed: List<Reply>, raw: String, cfg: PipelineConfig, retrieved: List<RetrievedExemplar>, trace: TurnTrace) {
        var replies = parsed.take(cfg.replies)
        if (replies.isEmpty()) {
            trace.parseFailed = true
            val fallback = parser.rawFallback(raw)
            if (fallback.isNotEmpty()) replies = listOf(Reply(plain = fallback, yours = fallback))
        }
        val ids = retrieved.map { it.exemplar.id }
        replies = replies.map { it.copy(usedExemplarIds = ids) }
        conversation.lastSuggested = replies.map { it.yours }
        _hud.update {
            it.copy(
                phase = Phase.SHOWING,
                replies = replies,
                selectedReply = null,
                exemplarsUsed = retrieved.map { r -> ExemplarView(r.exemplar.id, r.exemplar.text, r.score) },
                trace = trace.copy(),
            )
        }
    }

    // ---- single call ----------------------------------------------------------------------------

    private suspend fun processSingle(turn: AudioTurn, cfg: PipelineConfig, trace: TurnTrace) {
        stage = Stage.A
        trace.tStageAStart = clock.nowMs()
        val ctx = conversation.window(cfg.contextTurns)
        // One turn stale by design: exemplars for what THEY said last time.
        val retrieved = conversation.lastHeardL1?.let { retrieve(it, cfg) } ?: emptyList()
        trace.tRetrieved = clock.nowMs()
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.single(cfg, ctx, retrieved.map { it.exemplar }, voiceCard), maxTokens = 320, thinking = cfg.thinking, tag = RequestTag.SINGLE, jsonSchema = schema(cfg, Schemas.SINGLE)))
        if (gen == null) {
            trace.timedOut = true
            _hud.update { it.copy(phase = restingPhase(it), error = "generation timed out") }
            return
        }
        trace.tAFirstToken = gen.firstTokenMs; trace.tAEnd = gen.doneMs; trace.tBEnd = gen.doneMs
        trace.tokensA = gen.stats?.outputTokens ?: 0; trace.decodeTpsA = gen.stats?.decodeTokensPerSec

        val extracted = parser.extract(gen.text)
        val obj = extracted?.obj
        if (extracted?.repaired == true) trace.repaired = true
        val a = extracted?.let { parser.parseStageA(it) } ?: run {
            trace.parseFailed = true
            val raw = parser.rawFallback(gen.text)
            StageAResult(Speaker.THEM, "", raw, raw)
        }
        if (a.heard.isEmpty()) {
            _hud.update { it.copy(phase = restingPhase(it)) }
            return
        }
        conversation.add(a.speaker, a.lang, a.heard, a.heardL1, turn.endMs)
        _hud.update { it.copy(heard = Line(a.heard, a.lang, a.heardL1)) }
        if (a.speaker == Speaker.THEM) {
            showReplies(obj?.let { parser.parseReplies(it) } ?: emptyList(), gen.text, cfg, retrieved, trace)
        } else {
            myTurn(a)
        }
    }

    // ---- word-finding ---------------------------------------------------------------------------

    private suspend fun processWord(turn: AudioTurn, cfg: PipelineConfig, trace: TurnTrace) {
        stage = Stage.WORD
        trace.tStageAStart = clock.nowMs()
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.word(cfg, emptyList(), voiceCard), maxTokens = 60, thinking = cfg.thinking, tag = RequestTag.WORD, jsonSchema = schema(cfg, Schemas.WORD)))
        if (gen == null) {
            trace.timedOut = true
            _hud.update { it.copy(phase = restingPhase(it)) }
            return
        }
        trace.tAFirstToken = gen.firstTokenMs; trace.tAEnd = gen.doneMs
        trace.tokensA = gen.stats?.outputTokens ?: 0; trace.decodeTpsA = gen.stats?.decodeTokensPerSec
        val hint = parser.parseWordHint(gen.text)
        if (hint == null) trace.parseFailed = true
        if (hint != null && hint.word.isNotEmpty() && hint.confidence >= wordConfidenceGate) {
            _hud.update { it.copy(phase = Phase.SHOWING, wordHint = hint.word, wordAlternatives = hint.alternatives, trace = trace.copy()) }
        } else {
            _hud.update { it.copy(phase = restingPhase(it)) }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** I said something: did it match a suggestion? Then it counts as learned. */
    private fun myTurn(a: StageAResult) {
        val idx = ReplyMatcher.match(a.heard, conversation.lastSuggested)
        if (idx != null) {
            conversation.markReplyUsed()
            conversation.lastSuggested.getOrNull(idx)?.let { _usedReplies.tryEmit(it) }
        }
        _hud.update { it.copy(phase = restingPhase(it), selectedReply = idx ?: it.selectedReply, learnedToday = conversation.learnedToday) }
    }

    private suspend fun retrieve(query: String, cfg: PipelineConfig): List<RetrievedExemplar> {
        val r = retriever ?: return emptyList()
        return try {
            r.forQuery(query, cfg.l1.code, cfg.exemplarsK)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            emptyList()
        }
    }

    private suspend fun generateOrNull(cfg: PipelineConfig, req: GenerateRequest): Generated? =
        withTimeoutOrNull(cfg.generationTimeoutMs) { model.generateText(req, clock) }

    private fun restingPhase(s: HudState): Phase =
        if (s.replies.isNotEmpty() || s.heard != null || s.wordHint != null) Phase.SHOWING else Phase.IDLE

    private fun cycle(current: Int?, size: Int, step: Int): Int? {
        if (size == 0) return null
        val c = current ?: if (step > 0) -1 else 0
        return Math.floorMod(c + step, size)
    }

    companion object {
        const val LEVEL_THROTTLE_MS = 100L
    }
}

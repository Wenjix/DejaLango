package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.conversation.ConversationState
import dev.dejalango.engine.conversation.ReplyMatcher
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.conversation.SuggestedReply
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
import dev.dejalango.engine.llm.TokenChunk
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
import dev.dejalango.engine.voice.VoiceCardBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

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

    /** The newest turn's job. Kept after [cancel] so the next turn can wait for it to unwind (see [start]). */
    @Volatile
    private var current: Job? = null

    /** One generation on the model at a time: turns and owner calls ([ownerModel]) never share the GPU. */
    private val modelLock = Mutex()

    /**
     * The model for the owner's own calls, such as voice-card builds. Each generation waits on the turns' lock, so a
     * turn that ends mid-build runs between two generations instead of beside one or behind the whole build.
     */
    val ownerModel: SpeechLanguageModel = object : SpeechLanguageModel by model {
        override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow { modelLock.withLock { emitAll(model.generate(req)) } }
    }

    /** Counts [start]s, so idle prep can tell a turn has ended since it was scheduled. */
    private val turnsStarted = AtomicLong()

    /** The newest turn that has reached the model; a resume also cancels a newer turn still waiting for it. */
    @Volatile
    private var enteredEpoch = 0L

    @Volatile
    private var idlePrep: Job? = null

    @Volatile
    private var stage: Stage = Stage.NONE
    private var turnCounter = 0L
    private var lastLevelMs = 0L

    /**
     * A card of the set on screen was used: read aloud or confirmed. [HudState.selectedReply] can't tell, since a tap or
     * the D-pad selects a card too. New cards reset it; a new line and Clear remove the cards it is about.
     */
    @Volatile
    private var replyUsed = false

    /**
     * Load the model and set the base prompt (system instruction) from [card]. Call again with a new card to change it;
     * a re-warm waits for the turn in flight, so it never resets that turn's HUD.
     */
    suspend fun warm(card: VoiceCard?) = modelLock.withLock {
        voiceCard = card
        _hud.update { it.copy(phase = Phase.WARMING, statusLine = "loading model", voiceCard = card, error = null) }
        try {
            model.warm(prompts.system(config.value, card))
            _hud.update { it.copy(phase = restingPhase(it), statusLine = null, model = model.info, mode = config.value.mode) }
            prepareUnlessTurnPending() // runs once this lock is released
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

            // A turn still waiting for the model (behind idle prep, a card build, an unwinding turn) hasn't reached
            // Stage A, but it is just as superseded by the resumed speech.
            is TurnEvent.SpeechResumed -> if (stage == Stage.A || enteredEpoch != turnsStarted.get()) cancel()
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
                // A set counts once: replies already matched from my speech (or confirmed) stay on the HUD but are spent.
                // The echo to expect is the card as offered, so a plain in my language can't absorb my next line.
                val offered = conversation.lastSuggested.getOrNull(idx) ?: return
                if (conversation.confirm(offered).isEmpty()) return
                replyUsed = true
                cancel()
                val spoken = if (s.showPlain) r.plain else r.yours
                conversation.add(Speaker.ME, config.value.l2.code, spoken, r.back ?: spoken, clock.nowMs())
                conversation.markReplyUsed()
                _usedReplies.tryEmit(r.yours)
                _hud.update {
                    it.copy(
                        phase = Phase.SHOWING,
                        learnedToday = conversation.learnedToday,
                        statusLine = "✓ you replied (#${idx + 1}) — listening for their response…",
                    )
                }
            }
            HudAction.TogglePlain -> _hud.update { it.copy(showPlain = !it.showPlain) }
            HudAction.Clear -> {
                cancel()
                conversation.dismissSuggestions()
                _hud.update { it.copy(phase = Phase.IDLE, heard = null, replies = emptyList(), selectedReply = null, wordHint = null, wordAlternatives = emptyList(), exemplarsUsed = emptyList(), statusLine = null, error = null) }
            }
            is HudAction.SetMode, HudAction.CutNow -> Unit // app-level: config and segmenter are owned there
        }
    }

    /** Reflect an external config change (mode, languages) on the HUD, and redo idle prep: another mode makes other requests next. */
    fun onConfigChanged(cfg: PipelineConfig) {
        _hud.update { it.copy(mode = cfg.mode) }
        prepareUnlessTurnPending()
    }

    /** Redo idle prep after work on [ownerModel] outside a turn (a voice-card build) that ended without a [warm]: it may have used up what prep made. */
    fun prepareNext() = prepareUnlessTurnPending()

    /**
     * Cancels the turn in flight. The job reference is kept: a cancelled generation can still be inside native
     * prefill, and the next [start] must join it rather than start a second conversation beside it on the GPU.
     */
    fun cancel() {
        current?.cancel()
    }

    private fun start(turn: AudioTurn, reason: EndReason) {
        val tEnd = clock.nowMs() // waiting below, for the previous turn or a card build, counts toward this turn's latency
        val epoch = turnsStarted.incrementAndGet()
        idlePrep?.cancel()
        val prev = current
        prev?.cancel()
        val job = scope.launch {
            prev?.join()
            // The lock, not the join, is the guarantee: a turn superseded while joining hands its place to the next
            // turn, which then waits here until the cancelled generation has really left the model.
            modelLock.withLock {
                enteredEpoch = epoch
                processTurn(turn, reason, tEnd)
            }
        }
        current = job
        // Also after cancel() (a resume, Confirm, Clear), which starts no turn, even one cancelled before it ran:
        // the idle gap should still refill whatever spare this turn used.
        job.invokeOnCompletion { if (turnsStarted.get() == epoch) prepareWhileIdle(epoch) }
    }

    /** Stops for good: cancels the turn in flight, and idle prep ends after the unit in progress (it can't be interrupted). */
    fun shutdown() {
        turnsStarted.incrementAndGet()
        idlePrep?.cancel()
        cancel()
    }

    private suspend fun processTurn(turn: AudioTurn, reason: EndReason, tEnd: Long) {
        val cfg = config.value
        val id = ++turnCounter
        val trace = TurnTrace(
            turnId = id, tTurnEnd = tEnd, audioMs = turn.durationMs, reason = reason.name,
            backend = model.info.backend, shape = if (cfg.mode == Mode.WORD_FINDING) "WORD" else cfg.shape.name,
        )
        _hud.update { it.copy(phase = Phase.THINKING, statusLine = TRANSCRIBING, trace = trace.copy(), error = null) }
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
            // Some paths set no status after TRANSCRIBING or GENERATING (an empty transcript, word finding, a timeout, a
            // cancel, a failure) and would leave the turn looking busy. Turns run one at a time under modelLock, so
            // either one here is still this turn's.
            _hud.update { it.copy(statusLine = if (it.statusLine == TRANSCRIBING || it.statusLine == GENERATING) null else it.statusLine, trace = trace.copy()) }
            _traces.tryEmit(trace.copy())
        }
    }

    /**
     * Idle-time model prep ([SpeechLanguageModel.prepare]), one unit per lock hold. It stops as soon as a turn has
     * started after [epoch], so that turn waits for the unit in progress at most, never for the whole batch.
     */
    private fun prepareWhileIdle(epoch: Long) {
        idlePrep?.cancel()
        idlePrep = scope.launch {
            try {
                // Checked before every unit: on the single model thread a waiting turn can't even reach the lock while
                // a unit blocks it, and a free lock doesn't check cancellation, so the epoch is what stops the loop.
                while (isActive && modelLock.withLock { turnsStarted.get() == epoch && config.value.let { model.prepare(upcomingRequests(it), it.structuredOutput) } }) {
                    // next unit
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // best effort: a request creates what it lacks itself
            }
        }
    }

    /**
     * Idle prep, unless a turn is unfinished: that turn redoes it when it completes. Armed while a turn is still joining
     * the previous one, a prep unit could take the model before that turn reaches it.
     */
    private fun prepareUnlessTurnPending() {
        if (current?.isCompleted != false) prepareWhileIdle(turnsStarted.get())
    }

    /** The requests the next turn will make, in order, so idle prep gets those ready first. */
    private fun upcomingRequests(cfg: PipelineConfig): List<String> = when {
        cfg.mode == Mode.WORD_FINDING -> listOf(RequestTag.WORD)
        cfg.shape == PipelineShape.SINGLE -> listOf(RequestTag.SINGLE)
        else -> listOf(RequestTag.STAGE_A, RequestTag.STAGE_B)
    }

    private fun schema(cfg: PipelineConfig, s: String): String? = if (cfg.structuredOutput) s else null

    // ---- two-stage ------------------------------------------------------------------------------

    private suspend fun processTwoStage(turn: AudioTurn, cfg: PipelineConfig, trace: TurnTrace) {
        stage = Stage.A
        trace.tStageAStart = clock.nowMs()
        // Stage A hears this turn only: shown earlier lines or the HUD's suggestions, a 2B model copies them into "heard".
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.stageA(cfg), maxTokens = 160, thinking = cfg.thinking, tag = RequestTag.STAGE_A, jsonSchema = schema(cfg, Schemas.STAGE_A)))
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

        // In both modes, if I just read aloud one of the cards on offer (a suggested Mandarin reply, or a SAY_IT_MY_WAY
        // variant), Stage A's language rule ("them if Mandarin") will label it THEM. Check ReplyMatcher first so my own
        // reading is recorded as ME and counted learned, and we wait for the other person's response instead of
        // generating replies to my own reply or recording it as their line.
        if (matchSpokenReply(a, turn.endMs, trace)) {
            return
        }

        // Matching runs first because Stage A labels my Mandarin as THEM; this language-based label still decides who gets Stage B.
        if (cfg.mode == Mode.REPLY_OPTIONS && a.speaker == Speaker.ME) {
            recordAside(a, turn.endMs, trace)
            return
        }
        if (cfg.mode == Mode.SAY_IT_MY_WAY && a.speaker == Speaker.THEM) {
            conversation.dismissSuggestions()
            conversation.add(a.speaker, a.lang, a.heard, a.heardL1, turn.endMs)
            _hud.update {
                it.copy(
                    heard = Line(a.heard, a.lang, a.heardL1),
                    replies = emptyList(),
                    selectedReply = null,
                    statusLine = null,
                    phase = Phase.SHOWING,
                    trace = trace.copy(),
                )
            }
            return
        }

        // A new line supersedes the cards on offer (cleared below), so they can't match what I say next.
        conversation.dismissSuggestions()
        val earlier = conversation.window(cfg.contextTurns) // stage B states this line on its own; don't repeat it as context
        conversation.add(a.speaker, a.lang, a.heard, a.heardL1, turn.endMs)
        _hud.update {
            it.copy(
                heard = Line(a.heard, a.lang, a.heardL1),
                replies = emptyList(),
                selectedReply = null,
                statusLine = GENERATING,
                trace = trace.copy(),
            )
        }

        when (cfg.mode) {
            Mode.REPLY_OPTIONS -> {
                replyStage(cfg, trace, query = a.heardL1) { ex -> prompts.stageB(cfg, a.heard, a.heardL1, earlier, ex) }
            }
            Mode.SAY_IT_MY_WAY -> {
                replyStage(cfg, trace, query = a.heardL1) { ex -> prompts.sayItMyWay(cfg, a.heardL1, ex) }
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
        // A phrasebook line in my language cannot be read to THEM, so replace it with the personal line on the card
        // when that line is in L2. If only the phrasebook line is in L2, use it as "yours" and discard its romanization
        // and back-translation, which describe the discarded line. Only detectable when L1 and L2 use different scripts.
        var replies = parsed.mapNotNull { r ->
            when {
                VoiceCardBuilder.inScriptOf(cfg.l2, r.yours) ->
                    if (VoiceCardBuilder.inScriptOf(cfg.l2, r.plain)) r else r.copy(plain = r.yours)
                VoiceCardBuilder.inScriptOf(cfg.l2, r.plain) -> r.copy(yours = r.plain, roman = null, back = null)
                else -> null
            }
        }.take(cfg.replies)
        if (replies.isEmpty()) {
            trace.parseFailed = true
            val fallback = parser.rawFallback(raw)
            if (fallback.isNotEmpty()) replies = listOf(Reply(plain = fallback, yours = fallback))
        }
        val ids = retrieved.map { it.exemplar.id }
        replies = replies.map { it.copy(usedExemplarIds = ids) }
        // Offer only L2 phrasings for matching and Confirm. A phrasebook line in my language is replaced by the
        // personal line on the card; a raw fallback that echoes my line stays on screen but is not offered.
        val offer = replies
            .filter { VoiceCardBuilder.inScriptOf(cfg.l2, it.yours) }
            .map { SuggestedReply(yours = it.yours, plain = it.plain) }
        replyUsed = false
        conversation.suggest(offer)
        _hud.update {
            it.copy(
                phase = Phase.SHOWING,
                replies = replies,
                selectedReply = null,
                // Reading aloud and Confirm only take cards on offer, so with none the prompt would lead nowhere.
                statusLine = if (offer.isNotEmpty()) YOUR_TURN else null,
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
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.single(cfg, ctx, retrieved.map { it.exemplar }), maxTokens = 320, thinking = cfg.thinking, tag = RequestTag.SINGLE, jsonSchema = schema(cfg, Schemas.SINGLE)))
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
        if (matchSpokenReply(a, turn.endMs, trace)) {
            return
        }
        if (a.speaker == Speaker.ME) {
            recordAside(a, turn.endMs, trace)
            return
        }
        conversation.add(a.speaker, a.lang, a.heard, a.heardL1, turn.endMs)
        _hud.update { it.copy(heard = Line(a.heard, a.lang, a.heardL1), statusLine = null) }
        showReplies(obj?.let { parser.parseReplies(it) } ?: emptyList(), gen.text, cfg, retrieved, trace)
    }

    /**
     * Records a line of mine that gets no replies. The cards on screen answer their line, so that line stays above
     * them; with no cards, mine shows, so I see it was heard. The turn began on "transcribing speech…", so the status
     * becomes my turn while the cards are on offer, waiting for their response once one was used, and blank for cards
     * that never went on offer.
     */
    private fun recordAside(a: StageAResult, tMs: Long, trace: TurnTrace) {
        conversation.add(Speaker.ME, a.lang, a.heard, a.heardL1, tMs)
        _hud.update {
            val cards = it.replies.isNotEmpty()
            it.copy(
                heard = if (cards) it.heard else Line(a.heard, a.lang, a.heardL1),
                statusLine = when {
                    !cards -> null
                    conversation.lastSuggested.isNotEmpty() -> YOUR_TURN
                    replyUsed -> THEIR_TURN
                    else -> null
                },
                trace = trace.copy(),
            ).let { s -> s.copy(phase = restingPhase(s)) }
        }
    }

    // ---- word-finding ---------------------------------------------------------------------------

    private suspend fun processWord(turn: AudioTurn, cfg: PipelineConfig, trace: TurnTrace) {
        stage = Stage.WORD
        trace.tStageAStart = clock.nowMs()
        val gen = generateOrNull(cfg, GenerateRequest(audio = turn, text = prompts.word(cfg, emptyList()), maxTokens = 60, thinking = cfg.thinking, tag = RequestTag.WORD, jsonSchema = schema(cfg, Schemas.WORD)))
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

    /**
     * Did the utterance just transcribed in [a] match a reply still on offer (either personalized `yours` or
     * phrasebook `plain`)? If so, consume the set, record the utterance in [conversation] as [Speaker.ME], mark it
     * learned, keep the HUD cards up with the matched reply highlighted, and wait for the other person's next turn
     * without running Stage B on my own voice. Once the set is consumed, the cards still rendered never match again.
     */
    private fun matchSpokenReply(a: StageAResult, tMs: Long, trace: TurnTrace): Boolean {
        val offered = conversation.lastSuggested
        if (offered.isEmpty()) return absorbConfirmedEcho(a, trace)
        // One pass over both phrasings, so the closest wins; a tie goes to the earlier reply, then to yours.
        val idx = ReplyMatcher.match(a.heard, offered.flatMap { listOf(it.yours, it.plain) })?.let { it / 2 } ?: return false
        if (conversation.takeSuggestions().isEmpty()) return false // confirmed or cleared on the HUD meanwhile
        replyUsed = true
        conversation.add(Speaker.ME, a.lang, a.heard, a.heardL1, tMs)
        conversation.markReplyUsed()
        _usedReplies.tryEmit(offered[idx].yours)
        _hud.update {
            it.copy(
                phase = restingPhase(it),
                selectedReply = idx,
                learnedToday = conversation.learnedToday,
                statusLine = "✓ you said reply #${idx + 1} — listening for their response…",
                trace = trace.copy(),
            )
        }
        return true
    }

    /**
     * Right after I confirm a reply on the HUD I usually say it. That utterance is the reply Confirm already recorded
     * and counted, not their next line, so it gets no Stage B. Checked once; anything else is a new line.
     */
    private fun absorbConfirmedEcho(a: StageAResult, trace: TurnTrace): Boolean {
        val echo = conversation.takeEcho() ?: return false
        ReplyMatcher.match(a.heard, listOf(echo.yours, echo.plain)) ?: return false
        _hud.update { it.copy(phase = restingPhase(it), statusLine = THEIR_TURN, trace = trace.copy()) }
        return true
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

    private suspend fun generateOrNull(cfg: PipelineConfig, req: GenerateRequest): Generated? {
        var partialText = ""
        var firstTokenMs: Long? = null
        val full = withTimeoutOrNull(cfg.generationTimeoutMs) {
            model.generateText(req, clock) { text ->
                if (firstTokenMs == null) firstTokenMs = clock.nowMs()
                partialText = text
            }
        }
        if (full != null) return full
        if (partialText.isNotBlank()) {
            return Generated(partialText, null, firstTokenMs, clock.nowMs())
        }
        return null
    }

    private fun restingPhase(s: HudState): Phase =
        if (s.replies.isNotEmpty() || s.heard != null || s.wordHint != null) Phase.SHOWING else Phase.IDLE

    private fun cycle(current: Int?, size: Int, step: Int): Int? {
        if (size == 0) return null
        val c = current ?: if (step > 0) -1 else 0
        return Math.floorMod(c + step, size)
    }

    companion object {
        private const val TRANSCRIBING = "transcribing speech…"
        private const val GENERATING = "generating replies…"
        private const val YOUR_TURN = "your turn: read a reply aloud (or tap it + 'I said this one')"
        private const val THEIR_TURN = "listening for their response…"
        const val LEVEL_THROTTLE_MS = 100L
    }
}

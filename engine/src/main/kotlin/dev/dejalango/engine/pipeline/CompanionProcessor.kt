package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.conversation.ReplyMatcher
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.conversation.Turn
import dev.dejalango.engine.hud.*
import dev.dejalango.engine.llm.*
import dev.dejalango.engine.prompts.CompanionPrompts
import dev.dejalango.engine.prompts.CompanionSchemas
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.trace.TurnTrace
import dev.dejalango.engine.turn.CaptureEvent
import dev.dejalango.engine.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Capture acknowledgments never wait for the model. Every generation uses the same lock. */
class CompanionProcessor(
    private val model: SpeechLanguageModel,
    private val config: StateFlow<PipelineConfig>,
    private val scope: CoroutineScope,
    private val retriever: Retriever? = null,
    promptBuilder: PromptBuilder = PromptBuilder(),
    private val clock: Clock = Clock.system(),
    private val hintOutcome: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    private val audioSlice: suspend (Long, Long) -> AudioTurn? = { _, _ -> null },
) {
    data class UsedReply(val text: String, val language: String, val offerId: Long)
    private data class Offer(val id: Long, val replies: List<Reply>, val source: Long)
    private data class Frozen(val cfg: PipelineConfig, val revision: Long, val offer: Offer?)
    private data class Work(val event: CaptureEvent.Ended, val frozen: Frozen, val version: Long)
    private data class Record(val line: Line, val capture: CaptureEvent.Ended?, val cfg: PipelineConfig, val usedOffer: Long? = null)
    private data class Running(val work: Work, val job: Job, var writing: Boolean = false)
    private data class BeforeCorrection(val id: Long, val source: Record, val hud: HudState, val records: List<Record>, val offer: Offer?, val spent: Set<Long>, val aside: Record?)

    private val gate = Any()
    private val modelLock = Mutex()
    private val ownerOperations = OwnerOperations()
    private var stoppedOwners: List<Job> = emptyList()
    private val parser = TurnResultParser()
    private val prompts = CompanionPrompts(promptBuilder)
    private val mutableHud = MutableStateFlow(HudState(phase = Phase.IDLE, pair = config.value.pair, synthetic = model is FakeSpeechModel, sessionId = UUID.randomUUID().toString()))
    val hud: StateFlow<HudState> = mutableHud.asStateFlow()
    private val traceEvents = MutableSharedFlow<TurnTrace>(extraBufferCapacity = 64)
    val traces: SharedFlow<TurnTrace> = traceEvents.asSharedFlow()
    private val usageEvents = MutableSharedFlow<UsedReply>(extraBufferCapacity = 16)
    val usedReplies: SharedFlow<UsedReply> = usageEvents.asSharedFlow()
    @Volatile private var active = config.value
    @Volatile private var levelAtMs: Long? = null
    private var revision = 0L
    private var offer: Offer? = null
    private var offerSequence = 0L
    private val spentOffers = mutableSetOf<Long>()
    private val frozen = HashMap<Long, Frozen>()
    private val versions = HashMap<Long, Long>()
    private val running = HashMap<Long, Running>()
    private val records = ArrayList<Record>()
    private val workSequence = AtomicLong()
    private val pending = AtomicInteger()
    @Volatile private var latestWritingId = 0L
    private var hintJob: Job? = null
    @Volatile private var hintEpoch = 0L
    private var idleJob: Job? = null
    private var preparedSystem = ""
    @Volatile private var stopped = false
    private var correctionSequence = 0L
    private var beforeCorrection: BeforeCorrection? = null
    private var repairJob: Job? = null
    private var repairVersion: Pair<Long, Long>? = null
    private var asideRecord: Record? = null
    @Volatile var voiceCard: VoiceCard? = null
        private set

    private val ownerModel: SpeechLanguageModel = object : SpeechLanguageModel by model {
        override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
            modelLock.withLock { emitAll(model.generate(req)) }
        }
    }

    suspend fun <T> ownerOperation(block: suspend (SpeechLanguageModel) -> T): T = ownerOperations.run { block(ownerModel) }

    /** Keep the model dispatcher alive until all SDK-flow and owner-operation cleanup has completed. */
    suspend fun awaitIdle() { stoppedOwners.joinAll(); modelLock.withLock {} }

    suspend fun warm(card: VoiceCard? = null) {
        mutableHud.update { it.copy(phase = Phase.WARMING, statusLine = "Getting ready…", error = null) }
        try {
            modelLock.withLock {
                if (stopped) throw CancellationException("Runtime stopped")
                voiceCard = card
                preparedSystem = prompts.system(active, card)
                model.warm(preparedSystem)
            }
            mutableHud.update { it.copy(phase = rest(it), voiceCard = card, modelReady = true, model = model.info, statusLine = null) }
            prepareNext()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableHud.update { it.copy(phase = Phase.ERROR, modelReady = false, error = "Couldn't prepare the local model. Restart listening to retry.", statusLine = null) } }
    }

    fun onConfigChanged(cfg: PipelineConfig) = synchronized(gate) {
        if (cfg.pair == active.pair) {
            active = cfg
            mutableHud.update { it.copy(pendingPair = null) }
        } else if (hud.value.listening) mutableHud.update { it.copy(pendingPair = cfg.pair) }
        else activate(cfg)
    }

    fun useVoiceCard(pair: dev.dejalango.engine.config.LanguagePair, card: VoiceCard?) = synchronized(gate) {
        if (active.pair != pair) return@synchronized
        voiceCard = card?.takeIf { it.l1 == active.l1.code }
        mutableHud.update { it.copy(voiceCard = voiceCard) }
    }

    fun listening(value: Boolean) {
        levelAtMs = null
        mutableHud.update {
            it.copy(
                listening = value,
                capture = if (value) it.capture else CaptureState(),
                hint = HintState(),
                level = if (value) it.level else 0f,
                error = if (value) null else it.error,
            )
        }
        if (!value) cancelHint()
    }

    fun inputError(message: String) { mutableHud.update { it.copy(error = message, listening = false, capture = CaptureState()) } }

    fun onInput(event: CaptureEvent) {
        if (stopped) return
        when (event) {
            is CaptureEvent.State -> mutableHud.update { it.copy(capture = event.capture) }
            is CaptureEvent.Level -> {
                val last = levelAtMs
                if (last == null || event.atMs < last || event.atMs - last >= 96) {
                    levelAtMs = event.atMs
                    mutableHud.update { it.copy(level = event.probability) }
                }
            }
            is CaptureEvent.Started -> synchronized(gate) {
                abandonCorrection()
                if (config.value.pair != active.pair) activate(config.value)
                frozen[event.id] = Frozen(active, revision, offer)
                while (frozen.size > 64) frozen.remove(frozen.keys.minOrNull())
                if (event.speaker == Speaker.ME) {
                    latestWritingId = event.id
                    running.values.filter { it.writing }.forEach { it.job.cancel() }
                }
                mutableHud.update { it.copy(correction = null, hint = HintState(), matched = false) }
            }
            is CaptureEvent.Ended -> enqueue(event)
            is CaptureEvent.WordPause -> hint(event)
            is CaptureEvent.HintCancelled -> cancelHint()
            is CaptureEvent.Resumed -> synchronized(gate) {
                running.values.filter { it.work.event.id == event.afterTurnId }.forEach { it.job.cancel() }
                versions[event.afterTurnId] = workSequence.incrementAndGet()
            }
        }
    }

    private fun activate(cfg: PipelineConfig) {
        revision++
        active = cfg
        running.values.toList().forEach { it.job.cancel() }
        cancelHint()
        offer = null; records.clear(); frozen.clear(); spentOffers.clear()
        abandonCorrection(); asideRecord = null
        voiceCard = voiceCard?.takeIf { it.l1 == cfg.l1.code }
        mutableHud.update { it.copy(pair = cfg.pair, pendingPair = null, pairRevision = revision, heard = null, wearer = null, aside = null, replies = emptyList(), selectedReply = null, hint = HintState(), correction = null, matched = false, voiceCard = voiceCard) }
    }

    private fun enqueue(event: CaptureEvent.Ended) = synchronized(gate) {
        if (stopped || !hud.value.modelReady) return@synchronized
        if (event.speaker == Speaker.ME) mutableHud.update { it.copy(lastWearerTurnId = event.id) }
        if (pending.get() >= 8) {
            mutableHud.update { it.copy(error = "Still catching up. Wait for the current words, then try again.") }
            return@synchronized
        }
        cancelHint(); idleJob?.cancel()
        val context = frozen[event.id] ?: Frozen(active, revision, offer)
        val work = Work(event, context, workSequence.incrementAndGet())
        versions[event.id] = work.version
        val writing = running.values.any { it.writing && it.job.isActive }
        if (event.speaker == Speaker.ME || event.audio.durationMs >= 1000 || !writing) {
            latestWritingId = event.id
            running.values.filter { it.writing }.forEach { it.job.cancel() }
        }
        launchWork(work) { process(work) }
    }

    private fun launchWork(work: Work, operation: suspend () -> Unit): Job {
        pending.incrementAndGet()
        mutableHud.update { it.copy(processing = true, phase = Phase.THINKING, error = null, statusLine = "Processing words…") }
        val job = scope.launch(start = CoroutineStart.LAZY) { operation() }
        running[work.version] = Running(work, job)
        job.invokeOnCompletion {
            val busy = synchronized(gate) {
                running.remove(work.version); versions.remove(work.event.id, work.version)
                val hasWork = pending.decrementAndGet() > 0
                mutableHud.update { it.copy(processing = hasWork, phase = if (hasWork) Phase.THINKING else rest(it), statusLine = if (hasWork) it.statusLine else null) }
                hasWork
            }
            if (!busy) prepareNext()
        }
        job.start()
        return job
    }

    private fun valid(work: Work): Boolean = !stopped && synchronized(gate) { revision == work.frozen.revision && versions[work.event.id] == work.version }
    private fun commit(work: Work, change: (HudState) -> HudState): Boolean = synchronized(gate) {
        if (!valid(work)) return@synchronized false
        mutableHud.update(change)
        true
    }

    private suspend fun process(work: Work) {
        val cfg = work.frozen.cfg
        val trace = TurnTrace(turnId = work.event.id, tTurnEnd = clock.nowMs(), audioMs = work.event.audio.durationMs, reason = work.event.reason, backend = model.info.backend, shape = if (work.event.speaker == Speaker.ME) "ME" else "THEM_TWO_STAGE")
        try {
            trace.tStageAStart = clock.nowMs()
            val single = cfg.shape == PipelineShape.SINGLE && work.event.speaker == Speaker.THEM
            val text = if (single) prompts.single(cfg, context(cfg), emptyList()) else prompts.transcribe(cfg, work.event.speaker == Speaker.ME)
            val gen = generate(cfg, GenerateRequest(work.event.audio, text, if (single) 320 else 180, cfg.thinking, if (single) RequestTag.SINGLE else if (work.event.speaker == Speaker.ME) RequestTag.YOURS else RequestTag.THEIRS))
            trace.tAFirstToken = gen.firstTokenMs; trace.tAEnd = gen.doneMs; trace.tokensA = gen.stats?.outputTokens ?: 0
            trace.decodeTpsA = gen.stats?.decodeTokensPerSec
            if (!valid(work)) return
            var result = parser.parseStageA(gen.text)
            if (!single && result != null && result.heard.isNotBlank() &&
                result.lang.substringBefore('-').lowercase() == cfg.l2.base && !VoiceCardBuilder.inScriptOf(cfg.l2, result.heard)) {
                val retry = generate(cfg, GenerateRequest(work.event.audio, prompts.transcribe(cfg, work.event.speaker == Speaker.ME, retry = true), 180, cfg.thinking,
                    if (work.event.speaker == Speaker.ME) RequestTag.YOURS else RequestTag.THEIRS))
                result = parser.parseStageA(retry.text)
                trace.tokensA += retry.stats?.outputTokens ?: 0; trace.tAEnd = retry.doneMs
                if (!valid(work)) return
            }
            if (result == null) {
                trace.parseFailed = true
                commit(work) { it.copy(error = "Couldn't understand that line. Tap before speaking and try again.") }
                return
            }
            if (result.heard.isBlank()) return
            trace.repaired = result.repaired
            val language = result.lang.substringBefore('-').lowercase()
            if (language.isNotEmpty() && language !in setOf(cfg.l1.base, cfg.l2.base)) {
                commit(work) { it.copy(error = "Couldn't place that line in this language pair. Check the pair, then try again.") }
                return
            }
            if (language == cfg.l2.base && !VoiceCardBuilder.inScriptOf(cfg.l2, result.heard)) {
                commit(work) { it.copy(error = "Couldn't recover the spoken script. Tap Talk and repeat that line.") }
                return
            }
            if (match(work, result)) { trace.shape = "ME_MATCHED"; return }
            if (work.event.speaker == Speaker.ME) {
                if (language == cfg.l1.base || language.isEmpty() && cfg.l1.cjk != cfg.l2.cjk && VoiceCardBuilder.inScriptOf(cfg.l1, result.heard)) {
                    trace.shape = "ME_L1"
                    commit(work) {
                        val line = Line(result.heard, cfg.l1.tag, id = work.event.id, speaker = Speaker.ME, roman = result.roman)
                        asideRecord = Record(line, work.event, cfg)
                        it.copy(aside = line, wearer = null, hint = HintState())
                    }
                    write(work, result, ReplyKind.VARIANTS, trace)
                } else if (language == cfg.l2.base || language.isEmpty() && cfg.l1.cjk != cfg.l2.cjk && VoiceCardBuilder.inScriptOf(cfg.l2, result.heard)) {
                    trace.shape = "ME_L2"
                    val line = Line(result.heard, cfg.l2.tag, result.heardL1, work.event.id, Speaker.ME, result.roman)
                    commit(work) {
                        record(Record(line, work.event, cfg)); offer = null
                        it.copy(wearer = line, aside = null, replies = emptyList(), selectedReply = null, hint = HintState(), matched = false)
                    }
                } else commit(work) { it.copy(error = "Couldn't tell which language you used. Tap Talk and try the line again.") }
            } else if (cfg.catchForgottenTaps && language.isEmpty() && cfg.l1.cjk == cfg.l2.cjk) {
                // No language code, and one script for both languages: a forgotten tap reads like their line, so ask, don't guess.
                commit(work) { it.copy(error = "Couldn't tell whose line that was. Tap Talk before you speak, then try again.") }
            } else if (cfg.catchForgottenTaps && (language == cfg.l1.base || cfg.l1.cjk != cfg.l2.cjk && VoiceCardBuilder.inScriptOf(cfg.l1, result.heard))) {
                commit(work) {
                    val line = Line(result.heard, cfg.l1.tag, id = work.event.id, speaker = Speaker.ME)
                    asideRecord = Record(line, work.event, cfg)
                    it.copy(aside = line, wearer = null, statusLine = "Caught your forgotten tap. Tap before speaking next time.")
                }
                synchronized(gate) { latestWritingId = work.event.id }
                trace.shape = "ME_L1_CAUGHT"
                write(work, result, ReplyKind.VARIANTS, trace)
            } else {
                val line = Line(result.heard, if (cfg.catchForgottenTaps) cfg.l2.tag else result.lang.ifBlank { cfg.l2.tag }, result.heardL1, work.event.id, Speaker.THEM, result.roman)
                commit(work) {
                    record(Record(line, work.event, cfg))
                    it.copy(heard = line, aside = null, wearer = null, matched = false, hint = HintState())
                }
                if (single) { trace.shape = "THEM_SINGLE"; show(work, parser.parseReplies(gen.text), ReplyKind.REPLIES, trace) }
                else write(work, result, ReplyKind.REPLIES, trace)
            }
        } catch (_: TimeoutCancellationException) {
            trace.timedOut = true
            commit(work) { it.copy(error = "That took too long. Try another turn when you're ready.") }
        } catch (cancelled: CancellationException) { trace.cancelled = true; throw cancelled }
        catch (_: Exception) { trace.parseFailed = true; commit(work) { it.copy(error = "Couldn't finish that turn. Try again.") } }
        finally {
            trace.tRendered = clock.nowMs()
            commit(work) { it.copy(trace = trace.copy(), model = model.info) }
            traceEvents.tryEmit(trace.copy())
        }
    }

    private fun match(work: Work, result: StageAResult): Boolean = synchronized(gate) {
        if (!valid(work)) return@synchronized false
        val offered = work.frozen.offer ?: return@synchronized false
        if (result.lang.isNotBlank() && result.lang.substringBefore('-').lowercase() != work.frozen.cfg.l2.base) return@synchronized false
        if (offered.id in spentOffers || !VoiceCardBuilder.inScriptOf(work.frozen.cfg.l2, result.heard)) return@synchronized false
        val index = ReplyMatcher.match(result.heard, offered.replies.flatMap { listOf(it.yours, it.plain) })?.div(2)
            ?: result.roman?.takeIf { work.frozen.cfg.l2.base == "zh" }?.let { roman ->
                ReplyMatcher.match(ReplyMatcher.foldPinyin(roman), offered.replies.map { ReplyMatcher.foldPinyin(it.roman.orEmpty()) })
            } ?: return@synchronized false
        spentOffers += offered.id
        if (offer?.id == offered.id) offer = null
        val line = Line(result.heard, work.frozen.cfg.l2.tag, result.heardL1, work.event.id, Speaker.ME, result.roman)
        record(Record(line, work.event, work.frozen.cfg, offered.id))
        mutableHud.update { it.copy(wearer = line, aside = null, replies = emptyList(), selectedReply = null, matched = true, lastWearerTurnId = work.event.id, learnedToday = it.learnedToday + 1, hint = HintState()) }
        usageEvents.tryEmit(UsedReply(offered.replies[index].yours, work.frozen.cfg.l2.tag, offered.id))
        true
    }

    private suspend fun write(work: Work, result: StageAResult, kind: ReplyKind, trace: TurnTrace) {
        if (!valid(work) || work.event.id != latestWritingId) return
        synchronized(gate) { running[work.version]?.writing = true }
        val cfg = work.frozen.cfg
        trace.tBStart = clock.nowMs()
        val examples = modelLock.withLock { retriever?.forQuery(result.heardL1, cfg.l1.code, cfg.exemplarsK) ?: emptyList() }
        trace.tRetrieved = clock.nowMs()
        if (!valid(work) || work.event.id != latestWritingId) return
        commit(work) { it.copy(exemplarsUsed = examples.map { e -> ExemplarView(e.exemplar.id, e.exemplar.text, e.score) }) }
        val prompt = if (kind == ReplyKind.VARIANTS) prompts.sayIt(cfg, result.heard, hud.value.heard?.text, examples.map { it.exemplar })
            else prompts.replies(cfg, result.heard, result.heardL1, context(cfg).filter { it.id != work.event.id }, examples.map { it.exemplar })
        val tag = if (kind == ReplyKind.VARIANTS) RequestTag.SAY_IT else RequestTag.STAGE_B
        var generated = generate(cfg, GenerateRequest(text = prompt, maxTokens = 240, thinking = cfg.thinking, tag = tag))
        var parsed = parser.parseReplies(generated.text)
        if (parsed.isNotEmpty() && parsed.none { VoiceCardBuilder.inScriptOf(cfg.l2, it.yours) || VoiceCardBuilder.inScriptOf(cfg.l2, it.plain) }) {
            if (!valid(work) || work.event.id != latestWritingId) return
            generated = generate(cfg, GenerateRequest(text = "$prompt\nUse ${cfg.l2.promptName} script for every spoken line. Do not return my language as yours.", maxTokens = 240, thinking = cfg.thinking, tag = tag))
            parsed = parser.parseReplies(generated.text)
        }
        trace.tBFirstToken = generated.firstTokenMs; trace.tBEnd = generated.doneMs; trace.tokensB = generated.stats?.outputTokens ?: 0
        trace.decodeTpsB = generated.stats?.decodeTokensPerSec
        show(work, parsed, kind, trace)
    }

    private fun show(work: Work, parsed: List<Reply>, kind: ReplyKind, trace: TurnTrace) = synchronized(gate) {
        if (!valid(work) || work.event.id != latestWritingId) return@synchronized
        val cfg = work.frozen.cfg
        val replies = parsed.mapNotNull { r -> when {
            VoiceCardBuilder.inScriptOf(cfg.l2, r.yours) -> r.copy(plain = r.plain.takeIf { VoiceCardBuilder.inScriptOf(cfg.l2, it) } ?: r.yours, roman = r.roman.takeIf { cfg.l2.romanization != null })
            VoiceCardBuilder.inScriptOf(cfg.l2, r.plain) -> r.copy(yours = r.plain, roman = null, back = null)
            else -> null
        } }.take(cfg.replies)
        offer = replies.takeIf { it.isNotEmpty() }?.let { Offer(++offerSequence, it, work.event.id) }
        if (replies.isEmpty()) trace.parseFailed = true
        val question = if (kind == ReplyKind.REPLIES) records.lastOrNull { it.line.id == work.event.id }?.line else null
        mutableHud.update { it.copy(heard = question ?: it.heard, replies = replies, selectedReply = if (replies.isEmpty()) null else 0, replyKind = kind, offerId = offer?.id ?: 0, error = if (replies.isEmpty()) "No usable reply came back. Try another line." else null) }
    }

    private suspend fun generate(cfg: PipelineConfig, request: GenerateRequest): Generated = modelLock.withLock {
        if (stopped) throw CancellationException("Runtime stopped")
        if (RequestTag.usesReplySystem(request.tag)) {
            val expected = prompts.system(cfg, voiceCard)
            if (preparedSystem != expected) { model.warm(expected); preparedSystem = expected }
        }
        val schema = if (!cfg.structuredOutput) null else when (request.tag) {
            RequestTag.YOURS -> CompanionSchemas.transcript(true)
            RequestTag.THEIRS -> CompanionSchemas.transcript(cfg.catchForgottenTaps)
            RequestTag.SINGLE -> CompanionSchemas.transcript(cfg.catchForgottenTaps, single = true)
            RequestTag.STAGE_B, RequestTag.SAY_IT -> CompanionSchemas.replies
            else -> request.jsonSchema
        }
        withTimeout(cfg.generationTimeoutMs) { model.generateText(request.copy(jsonSchema = schema), clock) }
    }

    private fun context(cfg: PipelineConfig): List<Turn> = synchronized(gate) { records.takeLast(cfg.contextTurns).map { Turn(it.line.id, it.line.speaker, it.line.lang, it.line.text, it.line.translation, it.capture?.audio?.endMs ?: 0) } }
    private fun record(record: Record) = synchronized(gate) {
        records.removeAll { it.line.id == record.line.id }
        records += record
        while (records.size > 40) records.removeAt(0)
    }

    private fun hint(event: CaptureEvent.WordPause) {
        cancelHint()
        val cfg = synchronized(gate) { frozen[event.turnId]?.cfg ?: active }
        val epoch = ++hintEpoch
        if (pending.get() > 0 || !hud.value.modelReady) { hintOutcome(event.turnId, event.pauseId, false); return }
        mutableHud.update { it.copy(hint = HintState(HintPhase.PENDING, pauseId = event.pauseId)) }
        hintJob = scope.launch {
            var ready = false
            try {
                if (pending.get() > 0 || !modelLock.tryLock()) return@launch
                val result = try { withTimeout(4000) { model.generateText(GenerateRequest(event.audio, prompts.hint(cfg), 80, false, RequestTag.HINT, jsonSchema = CompanionSchemas.HINT.takeIf { cfg.structuredOutput }), clock) } }
                    finally { modelLock.unlock() }
                val hint = parser.parseWordHint(result.text)
                ready = epoch == hintEpoch && hint != null && hint.word.isNotBlank() && hint.confidence >= .45f && hint.lang.substringBefore('-') != cfg.l1.base && VoiceCardBuilder.inScriptOf(cfg.l2, hint.word)
                if (ready) mutableHud.update { it.copy(hint = HintState(HintPhase.READY, hint!!.word, hint.roman.takeIf { cfg.l2.romanization != null }, hint.meaning, event.pauseId)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ready = false }
            finally {
                if (epoch == hintEpoch) {
                    if (!ready) mutableHud.update { it.copy(hint = HintState()) }
                    hintOutcome(event.turnId, event.pauseId, ready)
                }
            }
        }
    }

    private fun cancelHint() { hintEpoch++; hintJob?.cancel(); hintJob = null; mutableHud.update { it.copy(hint = HintState()) } }

    fun onAction(action: HudAction) {
        when (action) {
            HudAction.Next, HudAction.Prev -> mutableHud.update { s -> s.copy(selectedReply = if (s.replies.isEmpty()) null else ((s.selectedReply ?: 0) + if (action == HudAction.Next) 1 else -1).coerceIn(0, s.replies.lastIndex)) }
            is HudAction.Select -> mutableHud.update { if (action.index in it.replies.indices) it.copy(selectedReply = action.index) else it }
            HudAction.ToggleDisplay -> mutableHud.update { it.copy(displayPaused = !it.displayPaused) }
            HudAction.Clear -> clear()
            is HudAction.Repair -> repair(action)
            is HudAction.Undo -> undo(action.correctionId)
            else -> Unit
        }
    }

    fun clear() = synchronized(gate) {
        running.values.toList().forEach { it.job.cancel() }; cancelHint(); abandonCorrection(); offer = null; asideRecord = null; records.clear(); frozen.clear(); versions.clear()
        mutableHud.update { it.copy(heard = null, wearer = null, aside = null, replies = emptyList(), selectedReply = null, correction = null, matched = false, error = null) }
    }

    private fun abandonCorrection() {
        beforeCorrection = null
        repairVersion?.let { (id, version) -> versions.remove(id, version) }
        repairVersion = null
        repairJob?.cancel(); repairJob = null
    }

    private fun restore(before: BeforeCorrection) {
        records.clear(); records.addAll(before.records)
        offer = before.offer
        spentOffers.clear(); spentOffers.addAll(before.spent)
        asideRecord = before.aside
        val current = hud.value
        mutableHud.value = before.hud.copy(capture = current.capture, listening = current.listening, displayPaused = current.displayPaused, level = current.level, processing = pending.get() > 0, correction = null)
    }

    private fun undo(id: Long) = synchronized(gate) {
        val before = beforeCorrection?.takeIf { it.id == id } ?: return@synchronized
        abandonCorrection()
        restore(before)
    }

    private fun repair(action: HudAction.Repair) = synchronized(gate) {
        if (hud.value.capture.myTurn) return@synchronized
        val continuing = beforeCorrection?.takeIf { it.source.line.id == action.lineId }
        if (pending.get() > 0 && continuing == null) return@synchronized
        val source = continuing?.source ?: records.lastOrNull { it.line.id == action.lineId }
            ?: asideRecord?.takeIf { it.line.id == action.lineId } ?: return@synchronized
        if (continuing == null && action.lineId !in listOfNotNull(hud.value.heard?.id, hud.value.wearer?.id, hud.value.aside?.id)) return@synchronized
        val before = continuing ?: BeforeCorrection(++correctionSequence, source, hud.value, records.toList(), offer, spentOffers.toSet(), asideRecord)
        repairVersion?.let { (id, version) -> versions.remove(id, version) }
        repairVersion = null
        repairJob?.cancel()
        if (continuing != null) restore(before)
        beforeCorrection = before
        val toMe = source.line.speaker != Speaker.ME
        if (toMe) {
            val line = source.line.copy(speaker = Speaker.ME)
            val ownLanguage = source.cfg.l1.base == line.lang.substringBefore('-')
            records.removeAll { it.line.id == line.id }
            if (!ownLanguage) record(source.copy(line = line)) else asideRecord = source.copy(line = line)
            offer = null
            mutableHud.update { it.copy(heard = records.lastOrNull { r -> r.line.speaker == Speaker.THEM }?.line, wearer = line.takeUnless { ownLanguage }, aside = line.takeIf { ownLanguage }, replies = emptyList(), selectedReply = null, correction = CorrectionReceipt(before.id, line.id, line.text, line.lang, Speaker.ME, line.roman), error = null) }
            // The untapped route can omit language detection. Re-run the retained capture with the wearer
            // language pair, so a repaired own-language line gets translation variants too.
            source.capture?.let { captured ->
                val work = Work(captured.copy(speaker = Speaker.ME, reason = "FLIP"), Frozen(source.cfg, revision, null), workSequence.incrementAndGet())
                latestWritingId = line.id; versions[line.id] = work.version; repairVersion = line.id to work.version
                mutableHud.update { it.copy(correction = it.correction?.copy(pending = true)) }
                repairJob = launchWork(work) {
                    try { process(work) }
                    finally {
                        if (valid(work)) mutableHud.update { it.copy(correction = it.correction?.copy(pending = false)) }
                    }
                }
            }
            return@synchronized
        }
        val whole = action.whole || continuing != null
        val event = source.capture
        val split = event?.pauses?.lastOrNull { it - event.audio.startMs >= 300 && event.audio.endMs - it >= 300 }
        mutableHud.update { it.copy(correction = CorrectionReceipt(before.id, source.line.id, if (whole) source.line.text else "", source.line.lang, Speaker.THEM, source.line.roman.takeIf { whole }, pending = !whole && split != null, canMoveWhole = !whole, message = if (!whole && split == null) "No usable split remains. You can move the whole line instead." else null)) }
        if (!whole && split == null) return@synchronized
        val lineId = if (whole) source.line.id else -before.id
        latestWritingId = lineId
        val work = Work(CaptureEvent.Ended(lineId, Speaker.THEM, event?.audio ?: AudioTurn(FloatArray(0), 0, 0, 16000), "FLIP", emptyList()), Frozen(source.cfg, revision, null), workSequence.incrementAndGet())
        versions[lineId] = work.version
        repairVersion = lineId to work.version
        repairJob = launchWork(work) {
            fun current() = valid(work) && synchronized(gate) { beforeCorrection?.id == before.id }
            val trace = TurnTrace(lineId, clock.nowMs(), event?.audio?.durationMs ?: 0, "FLIP", backend = model.info.backend, shape = "THEM_FLIP")
            try {
                val moved: Line
                if (whole) {
                    moved = source.line.copy(speaker = Speaker.THEM)
                    if (!current()) return@launchWork
                    synchronized(gate) {
                        if (!current()) return@launchWork
                        record(source.copy(line = moved, usedOffer = null))
                        source.usedOffer?.let { spentOffers.remove(it) }
                        offer = null
                        mutableHud.update { it.copy(heard = moved, wearer = it.wearer?.takeUnless { l -> l.id == moved.id }, aside = it.aside?.takeUnless { l -> l.id == moved.id }, replies = emptyList(), selectedReply = null, matched = false, learnedToday = before.hud.learnedToday - if (source.usedOffer != null) 1 else 0) }
                    }
                } else {
                    val prefixAudio = audioSlice(event!!.audio.startMs, split!!)
                    val tailAudio = audioSlice(split, event.audio.endMs)
                    if (!current()) return@launchWork
                    if (prefixAudio == null || tailAudio == null) {
                        mutableHud.update { it.copy(correction = it.correction?.copy(pending = false, message = "That audio has expired. You can move the whole line instead.")) }
                        return@launchWork
                    }
                    val prefix = parser.parseStageA(generate(source.cfg, GenerateRequest(prefixAudio, prompts.transcribe(source.cfg, true), 180, tag = RequestTag.YOURS)).text)
                    val tail = parser.parseStageA(generate(source.cfg, GenerateRequest(tailAudio, prompts.transcribe(source.cfg, false), 180, tag = RequestTag.THEIRS)).text)
                    if (!current()) return@launchWork
                    if (prefix == null || tail == null || prefix.heard.isBlank() || tail.heard.isBlank()) {
                        mutableHud.update { it.copy(correction = it.correction?.copy(pending = false, message = "Couldn't recover that split. Move the whole line or Undo.")) }
                        return@launchWork
                    }
                    moved = Line(tail.heard, source.cfg.l2.tag, tail.heardL1, lineId, Speaker.THEM, tail.roman)
                    val kept = source.line.copy(text = prefix.heard, translation = prefix.heardL1, roman = prefix.roman)
                    synchronized(gate) {
                        if (!current()) return@launchWork
                        record(source.copy(line = kept, capture = event.copy(audio = prefixAudio)))
                        record(Record(moved, work.event.copy(audio = tailAudio), source.cfg))
                        offer = null
                        mutableHud.update { it.copy(heard = moved, wearer = kept, replies = emptyList(), selectedReply = null, correction = it.correction?.copy(text = moved.text, roman = moved.roman, pending = false)) }
                    }
                }
                if (current()) write(work, StageAResult(Speaker.THEM, moved.lang, moved.text, moved.translation ?: moved.text, roman = moved.roman), ReplyKind.REPLIES, trace)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) mutableHud.update { it.copy(correction = it.correction?.copy(pending = false, message = "Couldn't finish the correction. Undo or try the whole line.")) } }
            finally { trace.tRendered = clock.nowMs(); traceEvents.tryEmit(trace) }
        }
    }

    fun prepareNext() {
        if (stopped || pending.get() != 0 || hintJob?.isActive == true || !hud.value.modelReady) return
        idleJob?.cancel()
        idleJob = scope.launch {
            try {
                val tags = if (hud.value.capture.myTurn) listOf(RequestTag.YOURS) else listOf(RequestTag.THEIRS, RequestTag.STAGE_B)
                while (isActive && pending.get() == 0 && modelLock.withLock { pending.get() == 0 && model.prepare(tags, active.structuredOutput) }) yield()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Generation prepares any missing resources itself. */ }
        }
    }

    fun shutdown() {
        stopped = true
        stoppedOwners = ownerOperations.stop()
        synchronized(gate) { running.values.toList().forEach { it.job.cancel() } }
        idleJob?.cancel(); cancelHint()
    }
    private fun rest(state: HudState): Phase = if (state.heard != null || state.wearer != null || state.replies.isNotEmpty()) Phase.SHOWING else Phase.IDLE
}

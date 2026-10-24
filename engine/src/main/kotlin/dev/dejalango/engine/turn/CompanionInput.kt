package dev.dejalango.engine.turn

import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.RingBuffer
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.CapturePhase
import dev.dejalango.engine.hud.CaptureState

sealed interface CaptureEvent {
    data class State(val capture: CaptureState) : CaptureEvent
    data class Started(val id: Long, val speaker: Speaker, val atMs: Long) : CaptureEvent
    data class Ended(val id: Long, val speaker: Speaker, val audio: AudioTurn, val reason: String, val pauses: List<Long>) : CaptureEvent
    data class WordPause(val turnId: Long, val pauseId: Long, val audio: AudioTurn, val atMs: Long) : CaptureEvent
    data class HintCancelled(val turnId: Long) : CaptureEvent
    data class Resumed(val afterTurnId: Long) : CaptureEvent
    data class Level(val probability: Float, val atMs: Long) : CaptureEvent
}

/** All calls belong to the serialized audio path. Control time is the last processed frame. */
data class CompanionTiming(
    val armMs: Long = 4000, val debounceMs: Long = 400,
    val mineMinimumMs: Long = 300, val theirMinimumMs: Long = 600,
    val mineSilenceMs: Long = 1200, val theirSilenceMs: Long = 700,
    val doneSlackMs: Long = 600, val preRollMs: Long = 300,
    val hintPauseMs: Long = 500, val hintSpeechMs: Long = 1500,
    val hintSpacingMs: Long = 2000, val hintHoldMs: Long = 2000, val hintCapMs: Long = 4000,
    val maximumMs: Long = 28000, val acknowledgmentMs: Long = 650,
)

class CompanionInput(val ring: RingBuffer = RingBuffer(seconds = 60), private val timing: CompanionTiming = CompanionTiming()) {
    var state: CaptureState = CaptureState()
        private set
    private data class Span(
        var id: Long,
        var speaker: Speaker,
        var start: Long,
        var from: Long,
        var lastSpeech: Long,
        var announced: Boolean = false,
        var silence: Long? = null,
        var doneAt: Long? = null,
        var hintAsked: Boolean = false,
        var previousRms: Float = 0f,
        var largestDrop: Float = 0f,
        var dropAt: Long? = null,
        val pauses: MutableList<Long> = ArrayList(),
    )
    private data class Closed(val span: Span, val emittedAt: Long)
    private var span: Span? = null
    private var nextId = 1L
    private var armUntil: Long? = null
    private var armedAt = 0L
    private var armFloor = Long.MIN_VALUE
    private var lastTap: Long? = null
    private var lastWearerEnd = Long.MIN_VALUE
    private var lastTheir: Closed? = null
    private var resumeCandidate: Closed? = null
    private var pauseId = 0L
    private var pendingHint: Long? = null
    private var hintUntil: Long? = null
    private var lastHintAt: Long? = null
    private var closedId: Long? = null
    private var closedAt = 0L

    fun reset(): List<CaptureEvent> {
        span = null; armUntil = null; lastTap = null; lastTheir = null; resumeCandidate = null
        pendingHint = null; hintUntil = null; closedId = null; lastHintAt = null
        lastWearerEnd = Long.MIN_VALUE; armFloor = Long.MIN_VALUE
        ring.reset()
        state = CaptureState()
        return listOf(CaptureEvent.HintCancelled(0), CaptureEvent.State(state))
    }

    fun frame(frame: AudioFrame, probability: Float): List<CaptureEvent> {
        ring.append(frame)
        val now = frame.endMs(ring.sampleRate)
        val prob = probability.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        state = state.copy(ready = true, frameTimeMs = now)
        val out = arrayListOf<CaptureEvent>(CaptureEvent.Level(prob, now))
        if (closedId != null && now - closedAt >= timing.acknowledgmentMs) closedId = null
        if (span == null && armUntil?.let { now >= it } == true) armUntil = null
        var current = span
        if (current == null && prob >= 0.5f) {
            val speaker = if (armUntil != null) Speaker.ME else Speaker.THEM
            val lower = if (speaker == Speaker.ME) armFloor else lastWearerEnd
            current = Span(nextId++, speaker, frame.tMs, maxOf(ring.startMs ?: frame.tMs, frame.tMs - timing.preRollMs, lower), now)
            resumeCandidate = if (speaker == Speaker.THEM) lastTheir?.takeIf { frame.tMs - it.emittedAt in 0..300 } else null
            span = current
        }
        if (current != null) {
            val rms = kotlin.math.sqrt(frame.pcm.sumOf { (it * it).toDouble() } / frame.pcm.size.coerceAtLeast(1)).toFloat()
            val drop = current.previousRms - rms
            if (drop > current.largestDrop && frame.tMs - current.start >= 300) {
                current.largestDrop = drop; current.dropAt = frame.tMs
            }
            current.previousRms = rms
            if (prob >= 0.35f) {
                if (current.silence != null) {
                    if (now - current.silence!! >= 128) current.pauses += current.silence!!
                    current.silence = null
                    current.hintAsked = false
                    cancelHint(out, current.id)
                }
                current.lastSpeech = now
                resumeCandidate?.let { prior ->
                    if (now - current.start >= 250) {
                        current.id = prior.span.id
                        current.start = prior.span.start
                        current.from = prior.span.from
                        current.announced = true
                        current.pauses.addAll(0, prior.span.pauses)
                        out += CaptureEvent.Resumed(prior.span.id)
                        resumeCandidate = null
                    }
                }
                announce(current, now, out)
                if (current.doneAt?.let { now >= it } == true) close(now, "MANUAL", out)
                else if (now - current.start >= timing.maximumMs) {
                    val speaker = current.speaker
                    close(now, "MAX_LENGTH", out)
                    span = Span(nextId++, speaker, now, now, now, announced = true)
                    out += CaptureEvent.Started(span!!.id, speaker, now)
                }
            } else {
                if (current.silence == null) current.silence = current.lastSpeech
                if (!current.announced) {
                    span = null
                    resumeCandidate = null
                    if (armUntil?.let { now >= it } == true) armUntil = null
                } else if (current.doneAt != null) close(current.lastSpeech, "MANUAL", out)
                else {
                    val silence = now - current.lastSpeech
                    val hintSpacing = lastHintAt?.let { now - it >= timing.hintSpacingMs } ?: true
                    if (current.speaker == Speaker.ME && silence >= timing.hintPauseMs && current.lastSpeech - current.start >= timing.hintSpeechMs && !current.hintAsked && hintSpacing) {
                        current.hintAsked = true
                        pendingHint = ++pauseId
                        lastHintAt = now
                        val from = maxOf(current.from, current.lastSpeech - 8000)
                        out += CaptureEvent.WordPause(current.id, pauseId, ring.turn(from, current.lastSpeech), current.lastSpeech)
                    }
                    val deadline = endDeadline(current)
                    if (now >= deadline) close(current.lastSpeech, if (current.speaker == Speaker.ME && silence >= timing.hintCapMs) "HOLD_CAP" else "SILENCE", out)
                }
            }
        }
        publish(out)
        return out
    }

    fun talk(): List<CaptureEvent> {
        if (!state.ready) return emptyList()
        val now = state.frameTimeMs
        if (lastTap?.let { now - it < timing.debounceMs } == true) return emptyList()
        lastTap = now
        val out = ArrayList<CaptureEvent>()
        lastTheir = null; resumeCandidate = null; closedId = null
        val current = span
        if (current?.speaker == Speaker.THEM && current.silence == null && now - current.start <= 300) {
            current.speaker = Speaker.ME
            armedAt = now
            armUntil = now + timing.armMs
            armFloor = now - timing.preRollMs
            current.from = maxOf(current.from, now - timing.preRollMs, lastWearerEnd)
            announce(current, now, out)
        } else {
            if (current != null) close(if (current.silence != null) current.lastSpeech else now, "TAP", out, forceMine = true)
            armUntil = now + timing.armMs
            armedAt = now
            armFloor = if (current != null) now else now - timing.preRollMs
        }
        cancelHint(out, current?.id ?: 0)
        publish(out)
        return out
    }

    fun done(): List<CaptureEvent> {
        if (!state.ready || !state.myTurn) return emptyList()
        val out = ArrayList<CaptureEvent>()
        val current = span
        if (current == null || !current.announced) {
            span = null; armUntil = null
        } else {
            cancelHint(out, current.id)
            if (current.silence != null) close(current.lastSpeech, "MANUAL", out)
            else if (current.doneAt == null) current.doneAt = state.frameTimeMs + timing.doneSlackMs
        }
        publish(out)
        return out
    }

    fun hintResult(turnId: Long, id: Long, ready: Boolean): List<CaptureEvent> {
        val current = span ?: return emptyList()
        if (current.id != turnId || pendingHint != id || current.silence == null) return emptyList()
        pendingHint = null
        hintUntil = if (ready) minOf(state.frameTimeMs + timing.hintHoldMs, current.lastSpeech + timing.hintCapMs) else null
        val out = ArrayList<CaptureEvent>()
        if (state.frameTimeMs >= endDeadline(current)) close(current.lastSpeech, "SILENCE", out)
        publish(out)
        return out
    }

    fun operatorCut(): List<CaptureEvent> {
        if (!state.ready) return emptyList()
        if (state.myTurn) return done()
        val out = ArrayList<CaptureEvent>()
        if (span != null) close(span!!.lastSpeech, "MANUAL", out)
        else {
            val end = state.frameTimeMs
            val start = maxOf(ring.startMs ?: end, end - 3000, lastWearerEnd)
            if (end - start >= 300) {
                val id = nextId++
                out += CaptureEvent.Started(id, Speaker.THEM, start)
                out += CaptureEvent.Ended(id, Speaker.THEM, ring.turn(start, end), "MANUAL", emptyList())
            }
        }
        publish(out)
        return out
    }

    fun slice(from: Long, to: Long): AudioTurn? {
        if (from < (ring.startMs ?: Long.MAX_VALUE) || to > (ring.endMs ?: Long.MIN_VALUE) || to <= from) return null
        return ring.turn(from, to).takeIf { it.pcm.isNotEmpty() }
    }

    private fun announce(current: Span, now: Long, out: MutableList<CaptureEvent>) {
        val minimum = if (current.speaker == Speaker.ME) timing.mineMinimumMs else timing.theirMinimumMs
        if (!current.announced && now - current.start >= minimum) {
            current.announced = true
            if (current.speaker == Speaker.ME) armUntil = null
            out += CaptureEvent.Started(current.id, current.speaker, current.start)
        }
    }

    private fun endDeadline(current: Span): Long {
        val ordinary = current.lastSpeech + if (current.speaker == Speaker.ME) timing.mineSilenceMs else timing.theirSilenceMs
        return if (pendingHint != null) current.lastSpeech + timing.hintCapMs else maxOf(ordinary, hintUntil ?: ordinary)
    }

    private fun close(end: Long, reason: String, out: MutableList<CaptureEvent>, forceMine: Boolean = false) {
        val current = span ?: return
        val valid = current.announced || forceMine && current.speaker == Speaker.ME && end > current.start
        if (valid) {
            if (!current.announced) out += CaptureEvent.Started(current.id, current.speaker, current.start)
            val to = minOf(ring.endMs ?: end, end + if (current.speaker == Speaker.THEM) 100 else 0)
            val boundaries = current.pauses.toList().ifEmpty {
                listOfNotNull(current.dropAt?.takeIf { it - current.from >= 300 && to - it >= 300 })
            }
            out += CaptureEvent.Ended(current.id, current.speaker, ring.turn(current.from, to), reason, boundaries)
            if (current.speaker == Speaker.ME) {
                lastWearerEnd = to
                closedId = current.id; closedAt = state.frameTimeMs
                lastTheir = null
            } else lastTheir = if (reason == "SILENCE") Closed(current.copy(pauses = current.pauses.toMutableList()), state.frameTimeMs) else null
        }
        span = null; armUntil = null; resumeCandidate = null
        cancelHint(out, current.id)
    }

    private fun cancelHint(out: MutableList<CaptureEvent>, id: Long) {
        if (pendingHint != null || hintUntil != null) out += CaptureEvent.HintCancelled(id)
        pendingHint = null; hintUntil = null
    }

    private fun publish(out: MutableList<CaptureEvent>) {
        val current = span
        val phase = when {
            current?.announced == true && current.doneAt != null -> CapturePhase.FINISHING
            current?.announced == true && current.silence != null -> CapturePhase.CLOSING
            current?.announced == true -> CapturePhase.SPEAKING
            armUntil != null -> CapturePhase.ARMED
            else -> CapturePhase.LISTENING
        }
        state = state.copy(
            phase = phase,
            turnId = current?.id ?: state.turnId,
            speaker = current?.speaker ?: if (armUntil != null) Speaker.ME else Speaker.THEM,
            deadlineMs = when (phase) {
                CapturePhase.ARMED -> armUntil
                CapturePhase.FINISHING -> current?.doneAt
                CapturePhase.CLOSING -> current?.let(::endDeadline)
                else -> null
            },
            closedTurnId = closedId,
        )
        out += CaptureEvent.State(state)
    }
}

package dev.dejalango.engine.turn

import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.audio.RingBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Turns a stream of (frame, speech probability) into [TurnEvent]s.
 *
 * ```
 * SILENCE ──(prob ≥ on, held ≥ minSpeechMs)──► SPEECH ──(prob < off)──► PENDING_END
 *    ▲                                            ▲                          │
 *    │                                            └──(prob ≥ on)─────────────┤  before endSilenceMs
 *    └────────────── TurnEnded ◄─────────────────────────────────────────────┘  at endSilenceMs
 *                    then: speech onset within resumeGraceMs, held ≥ resumeConfirmMs → SpeechResumed
 * ```
 *
 * Pure and synchronous: feed it frames, get events. Time comes from frame timestamps, never the wall
 * clock, so behavior is identical on a phone and in a unit test.
 */
class TurnSegmenter(policy: TurnPolicy, private val ring: RingBuffer) {

    @Volatile
    var policy: TurnPolicy = policy

    private sealed interface State {
        object Silence : State
        data class Speech(val startMs: Long, var lastSpeechMs: Long, var announced: Boolean) : State
        data class PendingEnd(val startMs: Long, val silenceSinceMs: Long, val announced: Boolean) : State
    }

    private var state: State = State.Silence
    private var lastTurnEndMs: Long? = null
    private var lastEmitMs: Long? = null
    private var resumeCandidateSinceMs: Long? = null
    private var resumedTurnEndMs: Long? = null

    val isInSpeech: Boolean get() = state !is State.Silence

    fun reset() {
        state = State.Silence
        lastTurnEndMs = null
        lastEmitMs = null
        resumeCandidateSinceMs = null
        resumedTurnEndMs = null
    }

    /** Force the current turn to end now (bench button, "I'm done"). */
    fun cutNow(nowMs: Long): List<TurnEvent> {
        val s = state
        val out = ArrayList<TurnEvent>(1)
        when (s) {
            is State.Speech -> emitTurn(s.startMs, nowMs, EndReason.MANUAL, out)
            is State.PendingEnd -> emitTurn(s.startMs, s.silenceSinceMs, EndReason.MANUAL, out)
            State.Silence -> {}
        }
        state = State.Silence
        lastTurnEndMs = null
        return out
    }

    fun onFrame(frame: AudioFrame, prob: Float): List<TurnEvent> {
        val p = policy
        val now = frame.endMs(ring.sampleRate)
        val out = ArrayList<TurnEvent>(2)
        out += TurnEvent.Level(prob, now)

        when (val s = state) {
            State.Silence -> {
                if (prob >= p.onThreshold) {
                    val resumeOf = lastTurnEndMs?.takeIf { frame.tMs - it <= p.resumeGraceMs }
                    state = State.Speech(startMs = frame.tMs, lastSpeechMs = now, announced = false)
                    // A stale candidate from an unconfirmed blip must not fire later.
                    resumeCandidateSinceMs = if (resumeOf != null) frame.tMs else null
                    resumedTurnEndMs = resumeOf
                }
            }

            is State.Speech -> {
                if (prob >= p.offThreshold) {
                    s.lastSpeechMs = now
                    checkResume(now, out)
                    if (!s.announced && now - s.startMs >= p.minSpeechMs) {
                        s.announced = true
                        out += TurnEvent.SpeechStarted(s.startMs)
                    }
                    if (now - s.startMs >= p.maxTurnMs) {
                        emitTurn(s.startMs, now, EndReason.MAX_LENGTH, out)
                        state = State.Speech(startMs = now, lastSpeechMs = now, announced = true)
                        lastTurnEndMs = null // a forced cut is not an end; don't treat continuation as a resume
                    }
                } else {
                    state = State.PendingEnd(s.startMs, s.lastSpeechMs, s.announced)
                }
            }

            is State.PendingEnd -> {
                if (prob >= p.onThreshold) {
                    state = State.Speech(s.startMs, now, s.announced)
                } else if (now - s.silenceSinceMs >= p.endSilenceMs) {
                    val spoke = s.silenceSinceMs - s.startMs
                    if (spoke >= p.minSpeechMs) {
                        when (val w = p.window) {
                            WindowMode.FullTurn -> {
                                emitTurn(s.startMs, s.silenceSinceMs, EndReason.SILENCE, out)
                                lastTurnEndMs = now
                            }
                            is WindowMode.Trailing -> {
                                val last = lastEmitMs
                                if (last == null || now - last >= p.cooldownMs) {
                                    val from = maxOf(s.startMs, s.silenceSinceMs - w.windowMs)
                                    out += TurnEvent.TurnEnded(ring.turn(from, s.silenceSinceMs + p.tailMs), EndReason.PAUSE)
                                    lastEmitMs = now
                                }
                                lastTurnEndMs = null
                            }
                        }
                    }
                    state = State.Silence
                    resumeCandidateSinceMs = null
                }
            }
        }
        return out
    }

    private fun checkResume(now: Long, out: MutableList<TurnEvent>) {
        val since = resumeCandidateSinceMs ?: return
        if (now - since >= policy.resumeConfirmMs) {
            out += TurnEvent.SpeechResumed(tMs = since, afterTurnEndedAtMs = resumedTurnEndMs ?: since)
            resumeCandidateSinceMs = null
            resumedTurnEndMs = null
        }
    }

    private fun emitTurn(startMs: Long, endMs: Long, reason: EndReason, out: MutableList<TurnEvent>) {
        val p = policy
        val from = maxOf(ring.startMs ?: startMs, startMs - p.preRollMs)
        val to = minOf(ring.endMs ?: endMs, endMs + p.tailMs)
        out += TurnEvent.TurnEnded(ring.turn(from, to), reason)
    }
}

/** Glue: frames → ring buffer → VAD → segmenter → events. Runs on whatever dispatcher collects it. */
fun segmentTurns(
    frames: Flow<AudioFrame>,
    vad: VoiceActivityDetector,
    ring: RingBuffer,
    segmenter: TurnSegmenter,
): Flow<TurnEvent> = flow {
    frames.collect { frame ->
        ring.append(frame)
        val prob = vad.probability(frame.pcm)
        for (e in segmenter.onFrame(frame, prob)) emit(e)
    }
}

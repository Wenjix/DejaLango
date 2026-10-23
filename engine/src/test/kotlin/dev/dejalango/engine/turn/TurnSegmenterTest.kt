package dev.dejalango.engine.turn

import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.audio.RingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSegmenterTest {

    private val sr = 16_000
    private val frameMs = 32L
    private val frameSamples = 512

    private class Harness(policy: TurnPolicy) {
        val ring = RingBuffer(16_000, 30)
        val seg = TurnSegmenter(policy, ring)
        var t = 0L
        val events = ArrayList<TurnEvent>()

        /** Feed [ms] of frames with constant speech probability [prob]; samples carry the frame index for slicing checks. */
        fun feed(ms: Long, prob: Float) {
            var left = ms
            while (left > 0) {
                val f = AudioFrame(FloatArray(512) { (t / 32L).toFloat() }, t)
                ring.append(f)
                events += seg.onFrame(f, prob)
                t += 32
                left -= 32
            }
        }

        inline fun <reified T : TurnEvent> of(): List<T> = events.filterIsInstance<T>()
    }

    @Test
    fun `speech then silence yields one turn with pre-roll and tail`() {
        val h = Harness(TurnPolicy())
        h.feed(500, 0.0f)
        h.feed(1000, 0.9f)
        h.feed(800, 0.05f)
        val ended = h.of<TurnEvent.TurnEnded>()
        assertEquals(1, ended.size)
        assertEquals(EndReason.SILENCE, ended[0].reason)
        val turn = ended[0].turn
        // 300 ms pre-roll + 1000 ms speech + 100 ms tail, frame-quantized
        assertTrue("duration was ${turn.durationMs}", turn.durationMs in 1300L..1450L)
        assertEquals(turn.durationMs * sr / 1000, turn.pcm.size.toLong())
        assertEquals(1, h.of<TurnEvent.SpeechStarted>().size)
        // The slice must come from the right place in the ring: first sample belongs to a frame near t=200 ms.
        val firstFrameIdx = turn.pcm[0].toInt()
        assertTrue("first frame idx $firstFrameIdx", firstFrameIdx in 5..8)
    }

    @Test
    fun `a short blip is ignored`() {
        val h = Harness(TurnPolicy())
        h.feed(500, 0.0f)
        h.feed(200, 0.9f)
        h.feed(1500, 0.0f)
        assertEquals(0, h.of<TurnEvent.TurnEnded>().size)
        assertEquals(0, h.of<TurnEvent.SpeechStarted>().size)
    }

    @Test
    fun `a dip shorter than endSilence does not split the turn`() {
        val h = Harness(TurnPolicy())
        h.feed(1000, 0.9f)
        h.feed(300, 0.1f)
        h.feed(1000, 0.9f)
        h.feed(800, 0.1f)
        val ended = h.of<TurnEvent.TurnEnded>()
        assertEquals(1, ended.size)
        assertTrue("duration ${ended[0].turn.durationMs}", ended[0].turn.durationMs in 2300L..2500L)
    }

    @Test
    fun `hysteresis keeps speech alive between off and on thresholds`() {
        val h = Harness(TurnPolicy(onThreshold = 0.5f, offThreshold = 0.35f))
        h.feed(800, 0.9f)
        h.feed(600, 0.4f) // below on, above off: still speech
        h.feed(800, 0.1f)
        val ended = h.of<TurnEvent.TurnEnded>()
        assertEquals(1, ended.size)
        assertTrue("duration ${ended[0].turn.durationMs}", ended[0].turn.durationMs >= 1400)
    }

    @Test
    fun `resuming within the grace window emits SpeechResumed once confirmed`() {
        val h = Harness(TurnPolicy(resumeGraceMs = 300, resumeConfirmMs = 250))
        h.feed(1000, 0.9f)
        h.feed(704, 0.05f) // turn ends here
        assertEquals(1, h.of<TurnEvent.TurnEnded>().size)
        h.feed(128, 0.05f) // 128 ms later...
        h.feed(400, 0.9f)  // ...speech resumes and holds
        val resumed = h.of<TurnEvent.SpeechResumed>()
        assertEquals(1, resumed.size)
        assertTrue(resumed[0].afterTurnEndedAtMs <= resumed[0].tMs)
    }

    @Test
    fun `resuming after the grace window is a new turn, not a resume`() {
        val h = Harness(TurnPolicy(resumeGraceMs = 300))
        h.feed(1000, 0.9f)
        h.feed(704, 0.05f)
        h.feed(1000, 0.05f)
        h.feed(1000, 0.9f)
        h.feed(800, 0.05f)
        assertEquals(0, h.of<TurnEvent.SpeechResumed>().size)
        assertEquals(2, h.of<TurnEvent.TurnEnded>().size)
    }

    @Test
    fun `speech longer than maxTurn is cut and continues as a new turn`() {
        val h = Harness(TurnPolicy(maxTurnMs = 5_000))
        h.feed(7_000, 0.9f)
        h.feed(800, 0.05f)
        val ended = h.of<TurnEvent.TurnEnded>()
        assertEquals(2, ended.size)
        assertEquals(EndReason.MAX_LENGTH, ended[0].reason)
        assertEquals(EndReason.SILENCE, ended[1].reason)
        assertTrue(ended[0].turn.durationMs in 5_000L..5_500L)
        assertTrue(ended[1].turn.durationMs in 2_000L..2_500L)
        assertEquals(0, h.of<TurnEvent.SpeechResumed>().size)
    }

    @Test
    fun `word-finding emits a trailing window at a pause and respects cooldown`() {
        val h = Harness(TurnPolicy.WORD_FINDING.copy(cooldownMs = 5_000))
        h.feed(3_000, 0.9f)
        h.feed(600, 0.05f) // pause ≥ 500 ms → PAUSE window
        val first = h.of<TurnEvent.TurnEnded>()
        assertEquals(1, first.size)
        assertEquals(EndReason.PAUSE, first[0].reason)
        assertTrue("window ${first[0].turn.durationMs}", first[0].turn.durationMs in 3_000L..3_200L)
        h.feed(1_600, 0.9f) // resume (≥ minSpeech 1500)
        h.feed(600, 0.05f)  // pause again, but inside the 5 s cooldown
        assertEquals(1, h.of<TurnEvent.TurnEnded>().size)
        h.feed(3_000, 0.05f)
        h.feed(1_600, 0.9f)
        h.feed(600, 0.05f)  // now outside cooldown
        assertEquals(2, h.of<TurnEvent.TurnEnded>().size)
    }

    @Test
    fun `trailing window is capped at windowMs`() {
        val h = Harness(TurnPolicy.WORD_FINDING)
        h.feed(12_000, 0.9f)
        h.feed(600, 0.05f)
        val ended = h.of<TurnEvent.TurnEnded>()
        assertEquals(1, ended.size)
        assertTrue("window ${ended[0].turn.durationMs}", ended[0].turn.durationMs in 8_000L..8_200L)
    }

    @Test
    fun `cutNow forces a turn out of speech`() {
        val h = Harness(TurnPolicy())
        h.feed(1_500, 0.9f)
        val out = h.seg.cutNow(h.t)
        val ended = out.filterIsInstance<TurnEvent.TurnEnded>()
        assertEquals(1, ended.size)
        assertEquals(EndReason.MANUAL, ended[0].reason)
        assertNotNull(ended[0].turn)
        h.feed(800, 0.05f)
        assertEquals(0, h.of<TurnEvent.TurnEnded>().size)
    }

    @Test
    fun `level events are emitted every frame`() {
        val h = Harness(TurnPolicy())
        h.feed(320, 0.2f)
        assertEquals(10, h.of<TurnEvent.Level>().size)
    }

    @Test
    fun `ring buffer slices by time and wraps`() {
        val ring = RingBuffer(sr, 1) // 1 s capacity
        var t = 0L
        repeat(60) { i -> ring.append(AudioFrame(FloatArray(frameSamples) { i.toFloat() }, t)); t += frameMs }
        // 60 frames = 1.92 s written into a 1 s buffer: the first ~29 frames are gone.
        val s = ring.slice(t - 320, t)
        assertEquals(320 * sr / 1000, s.size)
        assertEquals(50f, s[0]) // frame index 50 starts at 1600 ms
        val tooOld = ring.slice(0, 100)
        assertEquals(0, tooOld.size)
    }
}

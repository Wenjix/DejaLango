package dev.dejalango.engine.turn

import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.hud.CapturePhase
import dev.dejalango.engine.conversation.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionInputTest {
    @Test fun `reset can replay audio from zero without keeping the prior wearer boundary`() {
        val r = Rig(); r.startMine(80); r.done(); r.frames(20, .8f)
        assertTrue(r.turns.isNotEmpty())
        r.input.reset(); r.events.clear(); r.time = 0
        r.frames(24, .8f); r.frames(23)
        assertTrue(r.turns.single().audio.pcm.isNotEmpty())
        assertTrue(r.turns.single().audio.startMs < r.turns.single().audio.endMs)
    }
    @Test fun `Talk acknowledges an arm from the last processed frame and requires input`() {
        val input = CompanionInput()
        input.talk()
        assertFalse(input.state.ready)
        input.frame(AudioFrame(FloatArray(512), 0), 0f)
        input.talk()
        assertEquals(CapturePhase.ARMED, input.state.phase)
        assertEquals(4032L, input.state.deadlineMs)
    }

    @Test fun `an unused arm survives silence until its four second deadline`() {
        val input = CompanionInput()
        input.frame(AudioFrame(FloatArray(512), 0), 0f)
        input.talk()
        input.frame(AudioFrame(FloatArray(3999 * 16), 32), 0f)
        assertEquals(CapturePhase.ARMED, input.state.phase)
        input.frame(AudioFrame(FloatArray(16), 4031), 0f)
        assertEquals(CapturePhase.LISTENING, input.state.phase)
    }

    @Test fun `tapped speech belongs to me and silence safely returns ownership to them`() {
        val input = CompanionInput()
        val events = mutableListOf<CaptureEvent>()
        var time = 0L
        fun frames(count: Int, probability: Float) = repeat(count) {
            events += input.frame(AudioFrame(FloatArray(512), time), probability)
            time += 32
        }
        frames(1, 0f)
        events += input.talk()
        frames(12, 0.8f)
        assertEquals(CapturePhase.SPEAKING, input.state.phase)
        frames(38, 0f)
        val mine = events.filterIsInstance<CaptureEvent.Ended>().single()
        assertEquals(Speaker.ME, mine.speaker)
        assertEquals("SILENCE", mine.reason)
        assertEquals(CapturePhase.LISTENING, input.state.phase)
        frames(23, 0.8f)
        frames(23, 0f)
        val theirs = events.filterIsInstance<CaptureEvent.Ended>().last()
        assertEquals(Speaker.THEM, theirs.speaker)
        assertTrue(theirs.audio.startMs >= mine.audio.endMs)
    }

    private class Rig {
        val input = CompanionInput()
        val events = mutableListOf<CaptureEvent>()
        var time = 0L
        fun frames(n: Int, probability: Float = 0f) = repeat(n) {
            events += input.frame(AudioFrame(FloatArray(512), time), probability)
            time += 32
        }
        fun talk() { events += input.talk() }
        fun done() { events += input.done() }
        fun startMine(n: Int = 20) { frames(1); talk(); frames(n, .8f) }
        val turns get() = events.filterIsInstance<CaptureEvent.Ended>()
    }

    @Test fun `Done cancels an unused arm and never manufactures speech`() {
        val r = Rig(); r.frames(1); r.talk(); r.done(); r.done(); r.frames(130)
        assertEquals(CapturePhase.LISTENING, r.input.state.phase)
        assertTrue(r.turns.isEmpty())
    }

    @Test fun `repeated Done preserves the original final word deadline and late Done is harmless`() {
        val r = Rig(); r.startMine(); r.done()
        val deadline = r.input.state.deadlineMs
        r.frames(9, .8f); r.done()
        assertEquals(deadline, r.input.state.deadlineMs)
        r.frames(10, .8f)
        assertEquals(1, r.turns.size)
        assertEquals("MANUAL", r.turns.single().reason)
        assertTrue(r.turns.single().audio.endMs in deadline!!..deadline + 32)
        r.done(); r.frames(70); r.done()
        assertEquals(1, r.turns.size)
    }

    @Test fun `Done at a pause closes immediately and clears a pending hint`() {
        val r = Rig(); r.startMine(52); r.frames(16)
        assertEquals(1, r.events.filterIsInstance<CaptureEvent.WordPause>().size)
        r.done()
        assertEquals(1, r.turns.size)
        assertEquals("MANUAL", r.turns.single().reason)
        assertTrue(r.events.any { it is CaptureEvent.HintCancelled })
        assertEquals(CapturePhase.LISTENING, r.input.state.phase)
    }

    @Test fun `empty hint releases hold at ordinary silence and stale hints cannot reopen a turn`() {
        val r = Rig(); r.startMine(52); r.frames(38)
        assertTrue(r.turns.isEmpty())
        val hint = r.events.filterIsInstance<CaptureEvent.WordPause>().single()
        r.events += r.input.hintResult(hint.turnId, hint.pauseId, false)
        assertEquals(1, r.turns.size)
        assertTrue(r.input.hintResult(hint.turnId, hint.pauseId, true).isEmpty())
    }

    @Test fun `a late ready hint never extends the four second silence cap`() {
        val r = Rig(); r.startMine(52); r.frames(110)
        val hint = r.events.filterIsInstance<CaptureEvent.WordPause>().single()
        r.events += r.input.hintResult(hint.turnId, hint.pauseId, true)
        r.frames(14)
        assertTrue(r.turns.isEmpty())
        r.frames(1)
        assertEquals("HOLD_CAP", r.turns.single().reason)
    }

    @Test fun `Talk during their speech closes theirs at the boundary without copying it into mine`() {
        val r = Rig(); r.frames(25, .8f); r.talk()
        assertEquals(Speaker.THEM, r.turns.single().speaker)
        val end = r.turns.single().audio.endMs
        r.frames(12, .8f); r.done(); r.frames(1)
        assertEquals(Speaker.ME, r.turns.last().speaker)
        assertTrue(r.turns.last().audio.startMs >= end)
    }

    @Test fun `a slightly late Talk relabels the candidate as mine and acknowledges immediately`() {
        val r = Rig(); r.frames(6, .8f); r.talk()
        assertEquals(CapturePhase.ARMED, r.input.state.phase)
        r.frames(8, .8f); r.frames(38)
        assertEquals(Speaker.ME, r.turns.single().speaker)
    }

    @Test fun `maximum length preserves wearer ownership across the continuation`() {
        val r = Rig(); r.startMine(890); r.done(); r.frames(1)
        assertTrue(r.turns.size >= 2)
        assertTrue(r.turns.all { it.speaker == Speaker.ME })
        assertEquals("MAX_LENGTH", r.turns.first().reason)
    }

    @Test fun `Talk debounce is frame timed and reset rejects commands before fresh input`() {
        val r = Rig(); r.frames(1); r.talk()
        val deadline = r.input.state.deadlineMs
        r.frames(6); r.talk()
        assertEquals(deadline, r.input.state.deadlineMs)
        r.frames(7); r.talk()
        assertTrue(r.input.state.deadlineMs!! > deadline!!)
        r.input.reset(); r.talk(); r.done()
        assertFalse(r.input.state.ready)
        assertTrue(r.turns.isEmpty())
    }
}

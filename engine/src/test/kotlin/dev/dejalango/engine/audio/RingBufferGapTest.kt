package dev.dejalango.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferGapTest {
    @Test fun `missing frames retain their time as silence instead of shifting later speech`() {
        val ring = RingBuffer(seconds = 1)
        ring.append(AudioFrame(FloatArray(160) { 1f }, 0))
        ring.append(AudioFrame(FloatArray(160) { 2f }, 20))
        val samples = ring.slice(0, 30)
        assertEquals(480, samples.size)
        assertTrue(samples.sliceArray(160 until 320).all { it == 0f })
        assertTrue(samples.sliceArray(320 until 480).all { it == 2f })
    }
}

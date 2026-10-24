package dev.dejalango.app

import dev.dejalango.app.hud.HudJson
import dev.dejalango.app.platform.audio.Wav
import dev.dejalango.app.platform.audio.WavFixtureAudioSource
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Line
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.hud.Reply
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Pure-JVM parts of :app (no Android classes), so they run with plain `./gradlew :app:testMobileDebugUnitTest`. */
class JvmAdaptersTest {

    @Test
    fun `wav encode decode round trip`() {
        val sr = 16_000
        val pcm = FloatArray(sr) { i -> (0.5 * sin(2 * Math.PI * 440 * i / sr)).toFloat() }
        val bytes = Wav.encode(pcm, sr)
        val back = Wav.decode(bytes)
        assertEquals(sr, back.sampleRate)
        assertEquals(1, back.channels)
        assertEquals(pcm.size, back.samples.size)
        var maxErr = 0f
        for (i in pcm.indices) maxErr = maxOf(maxErr, kotlin.math.abs(pcm[i] - back.samples[i]))
        assertTrue("max error $maxErr", maxErr < 1e-3f)
        val resampled = Wav.resample(back.samples, 16_000, 8_000)
        assertEquals(sr / 2, resampled.size)
    }

    @Test
    fun `fixture source frames the file and appends a second of silence`() = runBlocking {
        val sr = 16_000
        val pcm = FloatArray(sr * 2) { 0.1f } // 2 s
        val src = WavFixtureAudioSource(Wav.encode(pcm, sr), paced = false)
        src.start()
        val frames = src.frames.toList()
        val expectedFileFrames = (sr * 2) / 512
        assertEquals(expectedFileFrames + 32, frames.size)
        assertEquals(0L, frames[0].tMs - frames[0].tMs)
        assertEquals(32L, frames[1].tMs - frames[0].tMs)
        assertTrue(frames.last().pcm.all { it == 0f })
    }

    @Test
    fun `hud json encodes state and decodes actions`() {
        val s = HudState(
            phase = Phase.SHOWING, heard = Line("你是第一次来北京吗？", "zh", "Is this your first time in Beijing?"),
            replies = listOf(Reply("是的。", "对啊。", "Duì a.", "Yeah.")), selectedReply = 0, learnedToday = 2,
        )
        val j = HudJson.encode(s)
        assertTrue(j.contains("\"phase\":\"SHOWING\""))
        assertTrue(j.contains("对啊。"))
        assertTrue(j.contains("\"selected\":0"))
        assertEquals(HudAction.Next, HudJson.decodeAction("""{"action":"next","session":"test"}""", "test"))
        assertEquals(HudAction.Select(1), HudJson.decodeAction("""{"action":"select","index":1,"session":"test"}""", "test"))
        assertEquals(null, HudJson.decodeAction("""{"action":"confirm","session":"test"}""", "test"))
        assertEquals(null, HudJson.decodeAction("""{"action":"dance","session":"test"}""", "test"))
        assertEquals(null, HudJson.decodeAction("not json", "test"))
    }
}

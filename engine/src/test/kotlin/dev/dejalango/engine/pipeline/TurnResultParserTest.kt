package dev.dejalango.engine.pipeline

import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.voice.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnResultParserTest {
    private val p = TurnResultParser()

    @Test
    fun `clean stage A json`() {
        val r = p.parseStageA("""{"speaker":"them","lang":"zh","heard":"你是第一次来北京吗？","heard_l1":"Is this your first time in Beijing?"}""")
        assertNotNull(r)
        assertEquals(Speaker.THEM, r!!.speaker)
        assertEquals("zh", r.lang)
        assertEquals("你是第一次来北京吗？", r.heard)
        assertEquals("Is this your first time in Beijing?", r.heardL1)
    }

    @Test
    fun `fenced and prefixed json still parses`() {
        val text = "Sure, here you go:\n```json\n{\"speaker\":\"me\",\"lang\":\"en\",\"heard\":\"Yeah first time\",\"heard_l1\":\"Yeah first time\"}\n```\nLet me know if you need anything else."
        val r = p.parseStageA(text)
        assertNotNull(r)
        assertEquals(Speaker.ME, r!!.speaker)
        assertEquals("Yeah first time", r.heard)
    }

    @Test
    fun `missing translation falls back to heard`() {
        val r = p.parseStageA("""{"speaker":"them","heard":"hola"}""")
        assertEquals("hola", r!!.heardL1)
        assertEquals("", r.lang)
    }

    @Test
    fun `braces inside strings do not confuse extraction`() {
        val r = p.parseStageA("""{"speaker":"them","lang":"en","heard":"he said {wow} and left","heard_l1":"he said {wow} and left"} trailing {junk""")
        assertNotNull(r)
        assertEquals("he said {wow} and left", r!!.heard)
    }

    @Test
    fun `garbage returns null and raw fallback gives first line`() {
        assertNull(p.parseStageA("I'm sorry, I couldn't hear that."))
        assertEquals("I'm sorry, I couldn't hear that.", p.rawFallback("\n\nI'm sorry, I couldn't hear that.\nMore text"))
    }

    @Test
    fun `replies array with nulls and missing fields`() {
        val replies = p.parseReplies("""{"replies":[{"plain":"是的。","yours":"对啊。","roman":"Duì a.","back":"Yeah."},{"plain":"不是。","roman":null},{"yours":""}]}""")
        assertEquals(2, replies.size)
        assertEquals("对啊。", replies[0].yours)
        assertEquals("Duì a.", replies[0].roman)
        assertEquals("不是。", replies[1].yours) // yours falls back to plain
        assertNull(replies[1].roman)
    }

    @Test
    fun `a bare reply object is accepted as a single reply`() {
        val replies = p.parseReplies("""{"plain":"hola","yours":"ey, hola"}""")
        assertEquals(1, replies.size)
        assertEquals("ey, hola", replies[0].yours)
    }

    @Test
    fun `word hint and confidence`() {
        val w = p.parseWordHint("""{"word":"multimeter","alternatives":["voltmeter"],"confidence":0.8}""")
        assertNotNull(w)
        assertEquals("multimeter", w!!.word)
        assertEquals(listOf("voltmeter"), w.alternatives)
        assertEquals(0.8f, w.confidence, 1e-6f)
        val none = p.parseWordHint("""{"word":"","alternatives":[],"confidence":0}""")
        assertEquals(0f, none!!.confidence, 1e-6f)
    }

    @Test
    fun `voice card tolerates wrong types and clamps scales`() {
        val c = p.parseVoiceCard("""{"register":"9","directness":0,"fillers":["basically", 42, "honestly"],"signaturePhrases":"not a list","notes":"blunt but warm"}""", "en", "zh", Confidence.QUICK)
        assertNotNull(c)
        assertEquals(5, c!!.register)
        assertEquals(1, c.directness)
        assertEquals(listOf("basically", "honestly"), c.fillers)
        assertTrue(c.signaturePhrases.isEmpty())
        assertEquals(Confidence.QUICK, c.confidence)
        assertEquals("blunt but warm", c.notes)
    }

    @Test
    fun `truncated output is repaired when possible`() {
        // maxTokens hit before the closing brace
        val r = p.parseStageA("""{"speaker":"them","lang":"es","heard":"hola","heard_l1":"hello"""")
        assertNotNull(r)
        assertEquals("hola", r!!.heard)
        assertTrue(r.repaired)
        assertFalse(p.parseStageA("""{"speaker":"them","lang":"es","heard":"hola","heard_l1":"hello"}""")!!.repaired)
        // and mid-array in a replies answer
        val replies = p.parseReplies("""{"replies":[{"plain":"是的。","yours":"对啊。","roman":"Duì a.","back":"Yeah."},{"plain":"不是。","yours":"不是不""")
        assertTrue(replies.size >= 1)
        assertEquals("对啊。", replies[0].yours)
        // an extra closing brace is harmless
        assertNotNull(p.parseStageA("""{"speaker":"them","lang":"es","heard":"hola","heard_l1":"hello"}}"""))
    }
}

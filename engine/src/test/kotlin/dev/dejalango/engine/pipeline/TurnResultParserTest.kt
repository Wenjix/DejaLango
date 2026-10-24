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
    fun `a language choice copied from the prompt is no language`() {
        assertEquals("", p.parseStageA("""{"lang":"en|zh","heard":"你是第一次来北京吗","heard_l1":"Are you new to Beijing?","roman":null}""")!!.lang)
        assertEquals("zh-CN", p.parseStageA("""{"lang":"zh-CN","heard":"你好","heard_l1":"Hello"}""")!!.lang)
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
    fun `a dangling key opened before a closing brace is repaired`() {
        // Seen on device from Gemma 4 E2B: `"back":"…time.","},{…`
        val text = """{"replies":[{"plain":"你好，我不是第一次来。","yours":"你好呀，我来得还算新鲜。","roman":"Nǐ hǎo ya.","back":"Hello, it's not my first time.","},{"plain":"你呢？","yours":"你呢？你来过吗？","roman":"Nǐ ne?","back":"And you?",}]}"""
        val e = p.extract(text)
        assertNotNull(e)
        assertTrue(e!!.repaired)
        val replies = p.parseReplies(e.obj)
        assertEquals(2, replies.size)
        assertEquals("Hello, it's not my first time.", replies[0].back)
        assertEquals("你呢？你来过吗？", replies[1].yours)
    }

    @Test
    fun `a transcript copied from the prompt is empty`() {
        assertEquals("", p.parseTranscript("""{"lang":"en","text":"<verbatim transcript>"}""")!!.text)
        assertEquals("No worries.", p.parseTranscript("""{"lang":"en","text":"No worries."}""")!!.text)
    }

    @Test
    fun `a stage A skeleton copied from the prompt is no transcript`() {
        // Not null: that would show the raw text as their line and ask Stage B to answer it.
        val copied = p.parseStageA("""{"speaker":"them","lang":"<language code>","heard":"<verbatim transcript in the language spoken (Mandarin Chinese stays Mandarin Chinese), never translated>","heard_l1":"<English translation, or the transcript if already English>"}""")!!
        assertEquals("", copied.heard)
        assertEquals("", copied.heardL1)
        assertEquals("", copied.lang)
        // A copied translation alone falls back to the transcript.
        assertEquals("你好", p.parseStageA("""{"speaker":"them","lang":"zh","heard":"你好","heard_l1":"<English translation>"}""")!!.heardL1)
        // A verbatim copy keeps the prompts' "me"|"them", which is not JSON, in Stage A and in the single call.
        val verbatim = p.parseStageA("""{"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript in the language spoken (Mandarin Chinese stays Mandarin Chinese), never translated>","heard_l1":"<English translation, or the transcript if already English>"}""")!!
        assertEquals("", verbatim.heard)
        assertTrue(verbatim.repaired)
        val single = p.extract("""{"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript>","heard_l1":"<English translation>","replies":[{"plain":"<neutral phrasebook line>","yours":"<plain in my voice>","roman":"...","back":"<literal English translation of yours>"}]}""")!!
        assertEquals("", p.parseStageA(single)!!.heard)
        assertTrue(p.parseReplies(single.obj).isEmpty())
        // A real transcript under the copied choice is theirs, the prompt's pick when unclear.
        val real = p.parseStageA("""{"speaker":"me"|"them","lang":"zh","heard":"你好","heard_l1":"Hello"}""")!!
        assertEquals(Speaker.THEM, real.speaker)
        assertEquals("你好", real.heard)
    }

    @Test
    fun `values copied from the prompt skeleton count as missing`() {
        val replies = p.parseReplies("""{"replies":[{"plain":"¿Qué necesitas?","yours":"<plain in my voice>","roman":"...","back":"What do you need?"},{"plain":"<neutral phrasebook line>","yours":"…","roman":"…","back":"<literal English translation of yours>"}]}""")
        assertEquals(1, replies.size)
        assertEquals("¿Qué necesitas?", replies[0].yours) // falls back to plain
        assertNull(replies[0].roman)
        assertEquals("What do you need?", replies[0].back)
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

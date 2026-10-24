package dev.dejalango.engine.prompts

import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.conversation.Turn
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.Exemplar
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.VoiceCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val pb = PromptBuilder()
    private val cfg = PipelineConfig()
    private val card = VoiceCard(l1 = "en", l2 = "zh", register = 2, signaturePhrases = listOf("just ship it", "no worries"), confidence = Confidence.FULL)
    private val ctx = listOf(
        Turn(1, Speaker.THEM, "zh", "你是第一次来北京吗？", "Is this your first time in Beijing?", 0),
        Turn(2, Speaker.ME, "zh", "对啊，第一次来。", "Yeah, first time.", 1),
    )
    private val ex = listOf(Exemplar(1, "So basically we ship it.", "en", Source.TRANSCRIPT, FloatArray(0)))

    @Test
    fun `system prompt carries the card, the language names and the zh example`() {
        val s = pb.system(cfg, card)
        assertTrue(s.contains("Mandarin Chinese"))
        assertTrue(s.contains("My voice: casual"))
        assertTrue(s.contains("just ship it"))
        assertTrue(s.contains("\"roman\" is \"yours\" in Hanyu Pinyin"))
        assertTrue(s.contains("20 Mandarin Chinese characters"))
        assertTrue(s.contains("帮个忙"))
        assertFalse(s.contains("{{"))
    }

    @Test
    fun `no card leaves no voice-card line, and latin-script L2 gets the null roman rule and word cap`() {
        val es = PipelineConfig(l2 = Language.ES)
        val s = pb.system(es, null)
        assertTrue(s.contains("null (Spanish uses Latin script)"))
        assertTrue(s.contains("12 words"))
        assertTrue(s.contains("\"roman\":null"))
        assertFalse(s.contains("My voice"))
        assertEquals(pb.system(cfg, null), pb.system(cfg, VoiceCard.none("en", "zh")))
        assertTrue(pb.system(PipelineConfig(l2 = Language.JA), null).contains("\"roman\":\"<romanization>\""))
    }

    @Test
    fun `stage A hears only the audio and stage B answers the current line after the earlier ones`() {
        val a = pb.stageA(cfg, ctx)
        assertFalse("stage A carries no conversation", a.contains("北京"))
        assertTrue(a.contains("never translated"))

        val b = pb.stageB(cfg, "周末有什么打算？", "Any plans for the weekend?", ctx, ex)
        assertTrue(b.contains("Earlier:\nTHEM: 你是第一次来北京吗？\nME: 对啊，第一次来。\nTHEM now: 周末有什么打算？ (Any plans for the weekend?)"))
        assertTrue(b.indexOf("So basically we ship it.") < b.indexOf("THEM now:"))
        assertTrue(b.contains("Never translate or reuse my style samples or the example."))
        assertTrue(b.contains("with 2 replies"))
        assertTrue(b.trimEnd().endsWith("}]}"))
    }

    @Test
    fun `empty context, exemplars and card leave no headers behind`() {
        val b = pb.stageB(cfg, "你是第一次来北京吗？", "Is this your first time in Beijing?", emptyList(), emptyList())
        assertTrue(b.startsWith("THEM now: 你是第一次来北京吗？"))
        assertFalse(b.contains("Earlier"))
        assertFalse(b.contains("style samples"))
        assertTrue(b.contains("Never translate or reuse the example."))
        assertEquals("", pb.renderContext(emptyList()))
        assertEquals("", pb.renderExemplars(emptyList()))

        assertFalse(pb.single(cfg, emptyList(), emptyList()).contains("Conversation so far"))
        assertTrue(pb.single(cfg, ctx, ex).contains("Conversation so far (oldest first):\nTHEM: 你是第一次来北京吗？"))
        assertFalse(pb.sayItMyWay(cfg, "Any plans?", emptyList()).contains("Sentences I have actually said"))
        val w = pb.word(cfg, emptyList())
        assertTrue(w.contains("Using my voice, give the word"))
        assertTrue(pb.word(cfg, ex).contains("Using my voice and the sentences below"))
        assertTrue(pb.voiceCard(cfg, listOf("a", "b")).contains("- a\n- b"))
    }

    @Test
    fun `stage B prefill stays compact`() {
        // ~270 Gemma tokens on a first turn (it was ~610). A budget in characters keeps prompt edits honest.
        val firstTurn = pb.system(cfg, null) + pb.stageB(cfg, "你是第一次来北京吗？", "Is this your first time in Beijing?", emptyList(), emptyList())
        assertTrue("first-turn stage B prompt grew to ${firstTurn.length} chars", firstTurn.length < 1_200)
    }

    @Test
    fun `sections vanish when blank and tag-only lines leave no gaps`() {
        val set = PromptSet(mapOf("t" to "A {{x}}\n{{#card}}\nCard: {{card}}\n{{/card}}\n{{#ex}}Lines:\n{{ex}}\n{{/ex}}\n\n\n\nB"))
        assertEquals("A 1\nCard: c\nLines:\ne1\n\nB", set.render("t", mapOf("x" to "1", "card" to "c", "ex" to "e1")))
        assertEquals("A 1\n\nB", set.render("t", mapOf("x" to "1", "card" to "", "ex" to " ")))
    }

    @Test
    fun `malformed or unknown sections fail loudly`() {
        assertThrows(IllegalStateException::class.java) { PromptSet(mapOf("t" to "{{#x}}never closed")).render("t", mapOf("x" to "1")) }
        assertThrows(IllegalStateException::class.java) { PromptSet(mapOf("t" to "{{#y}}a{{/y}}")).render("t", emptyMap()) }
        // Nesting fails on the first render, even while the outer section is still blank.
        assertThrows(IllegalStateException::class.java) { PromptSet(mapOf("t" to "{{#a}}x{{#b}}y{{/b}}z{{/a}}")).render("t", mapOf("a" to "", "b" to "1")) }
    }

    @Test
    fun `asset overrides replace templates and unfilled placeholders fail loudly`() {
        val set = PromptSet.DEFAULT.withOverrides(mapOf(PromptSet.TRANSCRIBE to "custom {{l1_name}}"))
        val custom = PromptBuilder(set)
        var failed = false
        try {
            custom.transcribe()
        } catch (e: IllegalStateException) {
            failed = true
        }
        assertTrue("unfilled placeholder must throw", failed)
        assertTrue(PromptSet.DEFAULT.names.contains(PromptSet.SYSTEM))
    }
}

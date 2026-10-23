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
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val pb = PromptBuilder()
    private val cfg = PipelineConfig()
    private val card = VoiceCard(l1 = "en", l2 = "zh", register = 2, signaturePhrases = listOf("just ship it", "no worries"), confidence = Confidence.FULL)

    @Test
    fun `system prompt carries the card, the language names and the zh example`() {
        val s = pb.system(cfg, card)
        assertTrue(s.contains("Mandarin Chinese"))
        assertTrue(s.contains("just ship it"))
        assertTrue(s.contains("Hanyu Pinyin"))
        assertTrue(s.contains("20 Mandarin Chinese characters"))
        assertTrue(s.contains("你是第一次来北京吗"))
        assertFalse(s.contains("{{"))
    }

    @Test
    fun `latin-script L2 gets the null roman rule and word cap`() {
        val es = PipelineConfig(l2 = Language.ES)
        val s = pb.system(es, null)
        assertTrue(s.contains("null (Spanish uses Latin script)"))
        assertTrue(s.contains("12 words"))
        assertTrue(s.contains("no voice card yet"))
    }

    @Test
    fun `stage prompts render context and exemplars`() {
        val ctx = listOf(
            Turn(1, Speaker.THEM, "zh", "你是第一次来北京吗？", "Is this your first time in Beijing?", 0),
            Turn(2, Speaker.ME, "en", "Yeah, first time.", "Yeah, first time.", 1),
        )
        val a = pb.stageA(cfg, ctx)
        assertTrue(a.contains("THEM (zh): 你是第一次来北京吗？  [en: Is this your first time in Beijing?]"))
        assertTrue(a.contains("ME (en): Yeah, first time."))
        val ex = listOf(Exemplar(1, "So basically we ship it.", "en", Source.TRANSCRIPT, FloatArray(0)))
        val b = pb.stageB(cfg, "周末有什么打算？", "Any plans for the weekend?", ctx, ex, card)
        assertTrue(b.contains("- \"just ship it\" (signature)"))
        assertTrue(b.contains("- \"So basically we ship it.\""))
        assertTrue(b.contains("Write 2 different short replies"))
        assertEquals("(nothing yet)", pb.renderContext(emptyList(), cfg))
        assertEquals("(none yet)", pb.renderExemplars(emptyList(), null))
        val w = pb.word(cfg, ex, card)
        assertTrue(w.contains("reaching for a word"))
        val vc = pb.voiceCard(cfg, listOf("a", "b"))
        assertTrue(vc.contains("- a\n- b"))
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

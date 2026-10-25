package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.ReplySource
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.TokenChunk
import dev.dejalango.engine.playbook.Playbook
import dev.dejalango.engine.playbook.PlaybookIndex
import dev.dejalango.engine.turn.CaptureEvent
import dev.dejalango.engine.voice.HashingEmbedder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playbook's fast path: their Stage A stops at a confident text match with a scene line, before heard_l1. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionFastPathTest {
    /** Records what each request streamed as the processor read it, so a stopped generation shows where it stopped. */
    private class Recording(val fake: FakeSpeechModel, val heardFirst: Boolean = false) : SpeechLanguageModel by fake {
        val streamed = ArrayList<StringBuilder>()
        override fun generate(req: GenerateRequest): Flow<TokenChunk> {
            val text = StringBuilder().also { streamed += it }
            val chunks = if (heardFirst && req.tag == RequestTag.THEIRS) heardFirst(fake.generate(req)) else fake.generate(req)
            return chunks.onEach { text.append(it.text) }
        }

        /** The same Stage A with "heard" moved ahead of "lang", streamed a few characters at a time. */
        private fun heardFirst(chunks: Flow<TokenChunk>): Flow<TokenChunk> = flow {
            val all = chunks.toList()
            val json = Json.parseToJsonElement(all.joinToString("") { it.text }).jsonObject
            val text = JsonObject(mapOf("heard" to json.getValue("heard")) + json.filterKeys { it != "heard" }).toString()
            text.chunked(4).forEach { emit(TokenChunk(it)) }
            emit(all.last())
        }
    }

    private class Run(val hud: HudState, val model: Recording)

    private suspend fun TestScope.their(
        vararg lines: String, cfg: PipelineConfig = PipelineConfig(playbookScene = "restaurant"), lang: String = "zh", heardFirst: Boolean = false,
    ): Run {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), HashingEmbedder())
        val fake = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0, chunks = 40)
        for (l in lines) fake.enqueue(FakeSpeechModel.ScriptedTurn("them", lang, l, "GEMMA'S TRANSLATION"))
        val model = Recording(fake, heardFirst)
        val processor = CompanionProcessor(model, MutableStateFlow(cfg), this, playbook = { index })
        processor.warm()
        lines.forEachIndexed { i, _ ->
            processor.onInput(CaptureEvent.Ended(i + 1L, Speaker.THEM, AudioTurn(FloatArray(16_000), 0, 1000, 16_000), "SILENCE", emptyList()))
            advanceUntilIdle()
        }
        return Run(processor.hud.value, model)
    }

    @Test fun `an exact scene line shows its cards before heard_l1 is written, with no Stage B`() = runTest {
        val run = their("您好，几位？")
        assertEquals(Playbook.parse(PLAYBOOK).scene("restaurant")!!.moment("restaurant.greet")!!.replyCards(), run.hud.replies)
        assertEquals(ReplySource.PLAYBOOK, run.hud.replySource)
        assertEquals("THEM_PLAYBOOK_FAST", run.hud.trace?.shape)
        assertEquals("the seed line's translation, not Gemma's", "Hello, how many?", run.hud.heard?.translation)
        assertEquals(listOf(RequestTag.THEIRS), run.model.fake.requests.map { it.tag })
        assertFalse("stopped before heard_l1: ${run.model.streamed.single()}", run.model.streamed.single().contains("heard_l1"))
    }

    @Test fun `a near miss lets Stage A finish for the embedding match`() = runTest {
        val run = their("嗯，几位")
        assertNotEquals("THEM_PLAYBOOK_FAST", run.hud.trace?.shape)
        assertTrue(run.model.streamed.first().contains("heard_l1"))
    }

    @Test fun `an unrelated line lets Stage A finish`() = runTest {
        val run = their("今天天气真好")
        assertNotEquals("THEM_PLAYBOOK_FAST", run.hud.trace?.shape)
        assertTrue(run.model.streamed.first().contains("heard_l1"))
    }

    @Test fun `one of my seeded answers never takes the fast path`() = runTest {
        val run = their("一位。")
        assertNotEquals("THEM_PLAYBOOK_FAST", run.hud.trace?.shape)
        assertTrue(run.model.streamed.first().contains("heard_l1"))
    }

    @Test fun `a scene line labelled in my language lets Stage A finish for the forgotten-tap check`() = runTest {
        val mine = their("您好，几位？", lang = "en")
        assertNotEquals("THEM_PLAYBOOK_FAST", mine.hud.trace?.shape)
        assertTrue(mine.model.streamed.first().contains("heard_l1"))
        val unlabelled = their("您好，几位？", lang = "")
        assertEquals("in Chinese script, a line with no lang is theirs either way", "THEM_PLAYBOOK_FAST", unlabelled.hud.trace?.shape)
    }

    @Test fun `a heard that closes before lang waits for it`() = runTest {
        val theirs = their("您好，几位？", heardFirst = true)
        assertEquals("THEM_PLAYBOOK_FAST", theirs.hud.trace?.shape)
        assertFalse("stopped before heard_l1: ${theirs.model.streamed.single()}", theirs.model.streamed.single().contains("heard_l1"))
        val mine = their("您好，几位？", lang = "en", heardFirst = true)
        assertNotEquals("THEM_PLAYBOOK_FAST", mine.hud.trace?.shape)
    }

    @Test fun `with no scene on, or the fast path off, Stage A always finishes`() = runTest {
        for (cfg in listOf(PipelineConfig(), PipelineConfig(playbookScene = "restaurant", playbookFastPath = false))) {
            val run = their("您好，几位？", cfg = cfg)
            assertNotEquals("THEM_PLAYBOOK_FAST", run.hud.trace?.shape)
            assertTrue(run.model.streamed.first().contains("heard_l1"))
        }
    }

    @Test fun `after a fast stop the next turn transcribes and gets written replies`() = runTest {
        val run = their("您好，几位？", "今天天气真好")
        assertEquals(listOf(RequestTag.THEIRS, RequestTag.THEIRS, RequestTag.STAGE_B), run.model.fake.requests.map { it.tag })
        assertTrue(run.model.streamed[1].contains("heard_l1"))
        assertEquals(ReplySource.MODEL, run.hud.replySource)
        assertTrue(run.hud.replies.isNotEmpty())
    }

    private companion object {
        val PLAYBOOK = """
            {"version": 1, "l1": "en-US", "l2": "zh-CN", "scenes": [
              {"id": "restaurant", "title": "Restaurant", "setting": "A busy Sichuan place at lunch", "localRole": "server", "start": "restaurant.greet", "moments": [
                {"id": "restaurant.greet", "intent": "greets, asks party size",
                 "local": [{"id": "restaurant.greet.l1", "text": "您好，几位？", "l1": "Hello, how many?"}],
                 "replies": [
                  {"id": "restaurant.greet.r1", "text": "一位。", "roman": "Yí wèi.", "back": "Just one.", "next": "restaurant.order"},
                  {"id": "restaurant.greet.r2", "text": "两位。", "roman": "Liǎng wèi.", "back": "Two.", "next": "restaurant.order"}],
                 "others": []},
                {"id": "restaurant.order", "intent": "asks what to eat",
                 "local": [{"id": "restaurant.order.l1", "text": "您想吃点什么？", "l1": "What would you like to eat?"}],
                 "replies": [{"id": "restaurant.order.r1", "text": "来一个宫保鸡丁。", "roman": "Lái yí gè gōngbǎo jīdīng.", "back": "A kung pao chicken, please.", "next": ""}],
                 "others": []}]}]}
        """.trimIndent()
    }
}

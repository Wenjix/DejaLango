package dev.dejalango.engine.pipeline

import dev.dejalango.engine.config.*
import dev.dejalango.engine.conversation.*
import dev.dejalango.engine.hud.*
import dev.dejalango.engine.llm.*
import dev.dejalango.engine.prompts.*
import dev.dejalango.engine.turn.CaptureEvent
import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionContractsTest {
    @Test fun `all regional pairs fit the system budget and role-free constrained schemas`() {
        var count = 0
        for (me in Language.VARIETIES) for (them in Language.VARIETIES.filter { it.base != me.base }) {
            val cfg = PipelineConfig(l1 = me, l2 = them)
            val card = VoiceCard(me.code, them.code, notes = "A longer owner note. ".repeat(40), confidence = Confidence.FULL)
            val prompts = CompanionPrompts()
            val system = prompts.system(cfg, card)
            assertTrue(system.length <= 1200)
            assertTrue(system.contains(PromptSet.example(me, them)))
            assertFalse(prompts.transcribe(cfg, mine = true).contains("\"speaker\""))
            assertFalse(prompts.single(cfg, emptyList(), emptyList()).contains("\"speaker\""))
            for (detect in listOf(true, false)) {
                val schema = Json.parseToJsonElement(CompanionSchemas.transcript(detect)).jsonObject
                val props = schema["properties"]!!.jsonObject
                assertEquals(detect, props.containsKey("lang"))
                assertFalse(props.containsKey("speaker"))
            }
            count++
        }
        assertEquals(42, count)
        assertEquals("lu se", ReplyMatcher.foldPinyin("Lǜ sè"))
        assertEquals(0, ReplyMatcher.match(ReplyMatcher.foldPinyin("LU SE"), listOf(ReplyMatcher.foldPinyin("Lǜ sè"))))
    }

    @Test fun `target variant read without a tap matches in either Catch setting without style writes`() = runTest {
        for (catch in listOf(true, false)) {
            val model = FakeSpeechModel(0, 0)
            val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES, catchForgottenTaps = catch)), this)
            processor.warm()
            model.enqueue(FakeSpeechModel.ScriptedTurn("them", "en", "Do they have oat milk?", "Do they have oat milk?", CompanionProcessorTest.REPLIES))
            processor.onInput(end(1, Speaker.ME)); advanceUntilIdle()
            val offered = processor.hud.value.replies.first().yours
            model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", offered, "Coffee, please."))
            processor.onInput(end(2, Speaker.THEM)); advanceUntilIdle()
            assertTrue(processor.hud.value.matched)
            assertEquals(1, processor.hud.value.learnedToday)
            assertEquals(1, model.requests.count { RequestTag.usesReplySystem(it.tag) })
            assertEquals(null, processor.hud.value.voiceCard)
        }
    }

    @Test fun `wrong script transcription retries once and empty replies never use raw model prose`() = runTest {
        val model = FakeSpeechModel(0, 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(structuredOutput = true)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "What would you like?", "What would you like?"),
            FakeSpeechModel.ScriptedTurn("them", "zh", "请问想喝什么？", "What would you like?", """{"replies":[]}"""))
        processor.onInput(end(1, Speaker.THEM)); advanceUntilIdle()
        assertEquals(2, model.requests.count { it.tag == RequestTag.THEIRS })
        assertEquals("请问想喝什么？", processor.hud.value.heard?.text)
        assertTrue(processor.hud.value.replies.isEmpty())
        assertNotNull(processor.hud.value.error)
        assertTrue(model.requests.all { it.jsonSchema != null })
    }

    @Test fun `capture and display acknowledgments do not wait for owner-model work`() = runTest {
        val model = FakeSpeechModel(5000, 5000)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig()), this)
        processor.warm()
        val owner = launch { processor.ownerOperation { it.generateText(GenerateRequest(text = "A voice profile", tag = RequestTag.VOICE_CARD)) } }
        runCurrent()
        processor.onInput(CaptureEvent.State(CaptureState(CapturePhase.ARMED, ready = true)))
        processor.onAction(HudAction.ToggleDisplay)
        assertEquals(CapturePhase.ARMED, processor.hud.value.capture.phase)
        assertTrue(processor.hud.value.displayPaused)
        assertTrue(owner.isActive)
        advanceUntilIdle()
    }

    private fun end(id: Long, speaker: Speaker) = CaptureEvent.Ended(id, speaker, AudioTurn(FloatArray(16000), 0, 1000, 16000), "SILENCE", emptyList())

    @Test fun `processor shutdown waits for full owner operation after generation has returned`() = runTest {
        val model = FakeSpeechModel(0, 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig()), this)
        processor.warm()
        var indexing = false; var cleaned = false; var closed = false
        launch {
            processor.ownerOperation { owned ->
                owned.generateText(GenerateRequest(text = "Owner profile", tag = RequestTag.VOICE_CARD))
                indexing = true
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(500); cleaned = true } }
            }
        }
        runCurrent(); assertTrue(indexing)
        processor.shutdown()
        launch { processor.awaitIdle(); closed = true }
        runCurrent(); assertFalse(closed); assertFalse(cleaned)
        advanceUntilIdle(); assertTrue(cleaned); assertTrue(closed)
    }
}

package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.CaptureState
import dev.dejalango.engine.hud.CapturePhase
import dev.dejalango.engine.hud.ReplyKind
import dev.dejalango.engine.turn.CaptureEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompanionProcessorTest {
    @Test fun `level updates are throttled by accepted level time and reset when listening stops`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        processor.listening(true)

        processor.onInput(CaptureEvent.Level(0.2f, 32))
        processor.onInput(CaptureEvent.State(CaptureState(frameTimeMs = 32)))
        processor.onInput(CaptureEvent.Level(0.5f, 64))
        processor.onInput(CaptureEvent.State(CaptureState(frameTimeMs = 64)))
        assertEquals(0.2f, processor.hud.value.level, 0f)
        processor.onInput(CaptureEvent.Level(0.8f, 128))
        processor.onInput(CaptureEvent.State(CaptureState(frameTimeMs = 128)))
        assertEquals(0.8f, processor.hud.value.level, 0f)

        processor.listening(false)
        assertEquals(0f, processor.hud.value.level, 0f)
    }

    @Test fun `listening restart clears microphone error but stopping preserves it`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()

        processor.inputError("Microphone stopped…")
        processor.listening(true)
        assertEquals(null, processor.hud.value.error)

        processor.inputError("Microphone stopped…")
        processor.listening(false)
        assertEquals("Microphone stopped…", processor.hud.value.error)
    }

    @Test fun `their recognized line is retained and the first reply is immediately selected`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "¿Qué te gustaría tomar?", "What would you like to drink?", REPLIES))
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        processor.onInput(CaptureEvent.Ended(1, Speaker.THEM, audio(), "SILENCE", emptyList()))
        advanceUntilIdle()
        assertEquals("¿Qué te gustaría tomar?", processor.hud.value.heard?.text)
        assertEquals(0, processor.hud.value.selectedReply)
    }

    @Test fun `their line gets replies when the transcript copies the prompt's language choice`() = runTest {
        // What Gemma answers on the phone: the template's "en|zh" instead of one code.
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "en|zh", "你是第一次来北京吗", "Are you new to Beijing?"))
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig()), this)
        processor.warm()
        processor.onInput(CaptureEvent.Ended(1, Speaker.THEM, audio(), "SILENCE", emptyList()))
        advanceUntilIdle()
        assertEquals(null, processor.hud.value.error)
        assertEquals("你是第一次来北京吗", processor.hud.value.heard?.text)
        assertEquals(1, model.requests.count { it.tag == RequestTag.STAGE_B })
        assertTrue(processor.hud.value.replies.isNotEmpty())
    }

    @Test fun `reading a target language suggestion counts once with no writing request`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", "¿Qué te gustaría tomar?", "What would you like to drink?", REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        val reply = processor.hud.value.replies.first().yours
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", reply, "A coffee with milk, please."))
        processor.onInput(ended(2, Speaker.ME)); advanceUntilIdle()
        assertEquals(1, processor.hud.value.learnedToday)
        assertEquals(Speaker.ME, processor.hud.value.wearer?.speaker)
        assertEquals(1, model.requests.count { it.tag == RequestTag.STAGE_B })
        assertEquals("¿Qué te gustaría tomar?", processor.hud.value.heard?.text)
        processor.onInput(ended(3, Speaker.ME)); advanceUntilIdle()
        assertEquals(1, processor.hud.value.learnedToday)
    }

    @Test fun `original target language speech is recorded without generating replies`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "Un café con leche de avena.", "A coffee with oat milk."))
        processor.onInput(ended(1, Speaker.ME)); advanceUntilIdle()
        assertEquals("Un café con leche de avena.", processor.hud.value.wearer?.text)
        assertTrue(processor.hud.value.replies.isEmpty())
        assertFalse(model.requests.any { it.tag == RequestTag.STAGE_B || it.tag == RequestTag.SAY_IT })
    }

    @Test fun `an own language aside yields variants while preserving their question`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "¿Qué te gustaría tomar?", "What would you like to drink?", REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Do they have oat milk?", "Do they have oat milk?", REPLIES))
        processor.onInput(ended(2, Speaker.ME)); advanceUntilIdle()
        assertEquals(ReplyKind.VARIANTS, processor.hud.value.replyKind)
        assertEquals("Do they have oat milk?", processor.hud.value.aside?.text)
        assertEquals("¿Qué te gustaría tomar?", processor.hud.value.heard?.text)
        assertTrue(model.requests.any { it.tag == RequestTag.SAY_IT })
        val say = model.requests.last().text
        assertTrue(say.contains("They last said"))
    }

    @Test fun `forgotten tap setting changes routing without disabling reply matching`() = runTest {
        for (catch in listOf(true, false)) {
            val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
            val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES, catchForgottenTaps = catch)), this)
            processor.warm()
            model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Do they have oat milk?", "Do they have oat milk?", REPLIES))
            processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
            assertEquals(!catch, model.requests.any { it.tag == RequestTag.STAGE_B })
            assertEquals(catch, model.requests.any { it.tag == RequestTag.SAY_IT })
        }
    }

    @Test fun `split re-recognizes retained audio and Undo restores the exact merged original`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val slices = mutableListOf<Pair<Long, Long>>()
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this,
            audioSlice = { from, to -> slices += from to to; AudioTurn(FloatArray(((to - from) * 16).toInt()), from, to, 16000) })
        processor.warm()
        val merged = "Un café con leche. ¿Con azúcar?"
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", merged, "A coffee with milk. With sugar?"))
        processor.onInput(CaptureEvent.Ended(2, Speaker.ME, AudioTurn(FloatArray(48_000), 0, 3000, 16000), "MANUAL", listOf(1800)))
        advanceUntilIdle()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", "Un café con leche.", "A coffee with milk."),
            FakeSpeechModel.ScriptedTurn("them", "es", "¿Con azúcar?", "With sugar?", REPLIES))
        processor.onAction(HudAction.Repair(2)); advanceUntilIdle()
        assertEquals(listOf(0L to 1800L, 1800L to 3000L), slices)
        assertEquals("¿Con azúcar?", processor.hud.value.correction?.text)
        assertEquals("Un café con leche.", processor.hud.value.wearer?.text)
        assertEquals("¿Con azúcar?", processor.hud.value.heard?.text)
        assertEquals(0, processor.hud.value.learnedToday)
        processor.onAction(HudAction.Undo(processor.hud.value.correction!!.id))
        assertEquals(merged, processor.hud.value.wearer?.text)
        assertEquals(null, processor.hud.value.heard)
        assertTrue(processor.hud.value.replies.isEmpty())
    }

    @Test fun `split with blank prefix keeps the original wearer line`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this,
            audioSlice = { from, to -> AudioTurn(FloatArray(((to - from) * 16).toInt()), from, to, 16000) })
        processor.warm()
        val merged = "Un café con leche. ¿Con azúcar?"
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", merged, "A coffee with milk. With sugar?"))
        processor.onInput(CaptureEvent.Ended(2, Speaker.ME, AudioTurn(FloatArray(48_000), 0, 3000, 16000), "MANUAL", listOf(1800)))
        advanceUntilIdle()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", "", ""),
            FakeSpeechModel.ScriptedTurn("them", "es", "¿Con azúcar?", "With sugar?", REPLIES))

        processor.onAction(HudAction.Repair(2)); advanceUntilIdle()

        assertEquals(merged, processor.hud.value.wearer?.text)
        assertTrue(processor.hud.value.correction?.message?.contains("Couldn't recover") == true)
        assertFalse(processor.hud.value.correction!!.pending)
    }

    @Test fun `expired split offers whole line instead and stale Undo cannot change a newer turn`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "es", "Un café. ¿Con azúcar?", "Coffee. With sugar?"))
        processor.onInput(ended(1, Speaker.ME).copy(pauses = listOf(500))); advanceUntilIdle()
        processor.onAction(HudAction.Repair(1)); advanceUntilIdle()
        val receipt = processor.hud.value.correction!!
        assertTrue(receipt.canMoveWhole)
        assertTrue(receipt.message!!.contains("expired"))
        processor.onInput(CaptureEvent.Started(2, Speaker.ME, 1500))
        processor.onAction(HudAction.Undo(receipt.id))
        assertEquals(null, processor.hud.value.correction)
    }

    @Test fun `pair activation drops old results and keeps the turn in its frozen pair`() = runTest {
        val cfg = MutableStateFlow(PipelineConfig(l2 = Language.ES))
        val model = FakeSpeechModel(stageADelayMs = 1000, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, cfg, this)
        processor.warm(); processor.listening(true)
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "¿Qué quieres?", "What do you want?", REPLIES))
        processor.onInput(CaptureEvent.Started(1, Speaker.THEM, 0)); processor.onInput(ended(1, Speaker.THEM))
        cfg.value = cfg.value.copy(l2 = Language.ZH_TW); processor.onConfigChanged(cfg.value)
        assertEquals(Language.ES, processor.hud.value.pair.them)
        assertEquals(Language.ZH_TW, processor.hud.value.pendingPair?.them)
        processor.onInput(CaptureEvent.Started(2, Speaker.ME, 2000)); advanceUntilIdle()
        assertEquals(Language.ZH_TW, processor.hud.value.pair.them)
        assertEquals(null, processor.hud.value.heard)
        assertTrue(processor.hud.value.replies.isEmpty())
    }

    @Test fun `display pause leaves the turn intact and capture acknowledgment does not await recognition`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 5000, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        processor.onInput(ended(1, Speaker.THEM))
        processor.onInput(CaptureEvent.State(CaptureState(CapturePhase.ARMED, ready = true)))
        assertEquals(CapturePhase.ARMED, processor.hud.value.capture.phase)
        processor.onAction(HudAction.ToggleDisplay)
        assertTrue(processor.hud.value.displayPaused)
        assertEquals(CapturePhase.ARMED, processor.hud.value.capture.phase)
        advanceUntilIdle()
    }

    @Test fun `whole line correction and Undo restore exact words and affected usage`() = runTest {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "¿Qué te gustaría tomar?", "What would you like to drink?", REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        val reply = processor.hud.value.replies.first().yours
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", reply, "A coffee, please."))
        processor.onInput(ended(2, Speaker.ME)); advanceUntilIdle()
        assertEquals(1, processor.hud.value.learnedToday)
        processor.onAction(HudAction.Repair(2, whole = true)); advanceUntilIdle()
        assertEquals(reply, processor.hud.value.correction?.text)
        assertEquals(0, processor.hud.value.learnedToday)
        processor.onAction(HudAction.Undo(processor.hud.value.correction!!.id))
        assertEquals(reply, processor.hud.value.wearer?.text)
        assertEquals("¿Qué te gustaría tomar?", processor.hud.value.heard?.text)
        assertEquals(1, processor.hud.value.learnedToday)
    }

    private fun ended(id: Long, speaker: Speaker) = CaptureEvent.Ended(id, speaker, audio(), "SILENCE", emptyList())

    @Test fun `their line flipped to mine re-detects language and creates private variants`() = runTest {
        val model = FakeSpeechModel(0, 0)
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES, catchForgottenTaps = false)), this)
        processor.warm()
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "es", "Do you have oat milk?", "Do you have oat milk?", REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Do you have oat milk?", "Do you have oat milk?", REPLIES))
        processor.onAction(HudAction.Repair(1)); advanceUntilIdle()
        assertEquals("Do you have oat milk?", processor.hud.value.aside?.text)
        assertEquals(null, processor.hud.value.heard)
        assertEquals(ReplyKind.VARIANTS, processor.hud.value.replyKind)
        assertTrue(model.requests.any { it.tag == RequestTag.YOURS })
        val correction = processor.hud.value.correction!!.id
        processor.onAction(HudAction.Undo(correction))
        assertEquals("Do you have oat milk?", processor.hud.value.heard?.text)
        assertEquals(null, processor.hud.value.aside)
    }

    private fun audio() = AudioTurn(FloatArray(16_000), 0, 1000, 16_000)
    companion object {
        const val REPLIES = """{"replies":[{"plain":"Un café, por favor.","yours":"Un café con leche, por favor. Gracias.","roman":null,"back":"A coffee with milk, please. Thanks."},{"plain":"Agua, gracias.","yours":"Para mí, solo agua. Gracias.","roman":null,"back":"Just water for me, thanks."}]}"""
    }
}

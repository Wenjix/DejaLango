package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Line
import dev.dejalango.engine.hud.CaptureState
import dev.dejalango.engine.hud.CapturePhase
import dev.dejalango.engine.hud.ReplyKind
import dev.dejalango.engine.hud.ReplySource
import dev.dejalango.engine.playbook.Playbook
import dev.dejalango.engine.playbook.PlaybookIndex
import dev.dejalango.engine.turn.CaptureEvent
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.HashingEmbedder
import dev.dejalango.engine.voice.InMemoryExemplarStore
import dev.dejalango.engine.voice.Retriever
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.TextEmbedder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun `with one script for both languages, an untapped line with no language code asks again instead of guessing`() = runTest {
        // English and Spanish share a script, so a copied "en|es" can't tell a forgotten tap from their line.
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "en|es", "Do you have oat milk?", "Do you have oat milk?"))
        val processor = CompanionProcessor(model, MutableStateFlow(PipelineConfig(l2 = Language.ES)), this)
        processor.warm()
        processor.onInput(CaptureEvent.Ended(1, Speaker.THEM, audio(), "SILENCE", emptyList()))
        advanceUntilIdle()
        assertEquals("Couldn't tell whose line that was. Tap Talk before you speak, then try again.", processor.hud.value.error)
        assertEquals(null, processor.hud.value.heard)
        assertEquals(0, model.requests.count { it.tag == RequestTag.STAGE_B })
        assertTrue(processor.hud.value.replies.isEmpty())
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

    @Test fun `with no playbook scene their line goes to Stage B exactly as without a playbook`() = runTest {
        val embedder = FixedEmbedder()
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), embedder)
        var asked = 0
        val off = theirTurn("您好，几位", PipelineConfig()) { asked++; index }
        assertEquals(theirTurn("您好，几位", PipelineConfig()), off)
        assertEquals(ReplySource.MODEL, off.first.replySource)
        assertEquals(null, off.first.playbookNote)
        assertEquals("the playbook is not even asked for", 0, asked)
        assertTrue(embedder.queries.isEmpty())
    }

    @Test fun `their line that hits the scene shows its prepared replies at once, and reading one is a match`() = runTest {
        val embedder = FixedEmbedder()
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), embedder)
        val (model, processor) = warmed(PipelineConfig(playbookScene = "restaurant")) { index }
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "您好，几位", "Hello, how many?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        val shown = processor.hud.value
        assertEquals(index.playbook.scene("restaurant")!!.moment("restaurant.greet")!!.replyCards(), shown.replies)
        assertEquals(ReplySource.PLAYBOOK, shown.replySource)
        assertEquals(0, shown.selectedReply)
        assertEquals("您好，几位", shown.heard?.text)
        assertEquals("hit restaurant.greet 1.000 (next 0.000) 「您好，几位？」", shown.playbookNote)
        assertEquals("THEM_PLAYBOOK", shown.trace?.shape)
        assertNotNull(shown.trace?.tBEnd)
        assertEquals(listOf("您好，几位"), embedder.queries)
        assertEquals("Stage A only", listOf(RequestTag.THEIRS), model.requests.map { it.tag })

        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "zh", "一位。", "Just one."))
        processor.onInput(ended(2, Speaker.ME)); advanceUntilIdle()
        assertTrue(processor.hud.value.matched)
        assertEquals("ME_MATCHED", processor.hud.value.trace?.shape)
        assertEquals("一位。", processor.hud.value.wearer?.text)
        assertEquals(1, processor.hud.value.learnedToday)
        assertEquals(listOf(RequestTag.THEIRS, RequestTag.YOURS), model.requests.map { it.tag })
    }

    @Test fun `their line that misses the scene goes to Stage B as before, with the miss noted`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val (missed, requests) = theirTurn("嗯，几位", PipelineConfig(playbookScene = "restaurant")) { index }
        assertEquals("miss BELOW_THRESHOLD 0.600 (next 0.000) 「您好，几位？」", missed.playbookNote)
        assertEquals(theirTurn("嗯，几位", PipelineConfig()), missed.copy(playbookNote = null) to requests)
        assertEquals(listOf("我们两个人。"), missed.replies.map { it.yours })
        assertEquals(ReplySource.MODEL, missed.replySource)
    }

    @Test fun `with every scene on, their line from a second scene hits it`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val (model, processor) = warmed(PipelineConfig(playbookScene = PipelineConfig.ALL_SCENES)) { index }
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "您想喝点什么", "What would you like to drink?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        val shown = processor.hud.value
        assertEquals(index.playbook.scene("cafe")!!.moment("cafe.order")!!.replyCards(), shown.replies)
        assertEquals(ReplySource.PLAYBOOK, shown.replySource)
        assertEquals("the restaurant's food question is the runner-up", "hit cafe.order 1.000 (next 0.600) 「您想喝点什么？」", shown.playbookNote)
        assertEquals("THEM_PLAYBOOK", shown.trace?.shape)
        assertEquals("Stage A only", listOf(RequestTag.THEIRS), model.requests.map { it.tag })

        // The restaurant alone has nothing nearer than its food question, so Stage B answers.
        val (restaurant, requests) = theirTurn("您想喝点什么", PipelineConfig(playbookScene = "restaurant")) { index }
        assertEquals("miss BELOW_THRESHOLD 0.600 (next 0.000) 「您想吃点什么？」", restaurant.playbookNote)
        assertEquals(ReplySource.MODEL, restaurant.replySource)
        assertEquals(1, requests.count { it.tag == RequestTag.STAGE_B })
    }

    @Test fun `my seeded answer said without a tap is recorded as mine, with no writing`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val (model, processor) = warmed(PipelineConfig(playbookScene = "restaurant")) { index }
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "嗯，几位", "How many?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        // Untapped, so their line by default; and not a reading of the card on offer, 我们两个人。
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "一位", "Just one.", roman = "Yí wèi"))
        processor.onInput(ended(2, Speaker.THEM)); advanceUntilIdle()
        val caught = processor.hud.value
        assertEquals("ME_PLAYBOOK", caught.trace?.shape)
        assertEquals(Line("一位", "zh-CN", "Just one.", 2, Speaker.ME, "Yí wèi"), caught.wearer)
        assertEquals("their question is the heard line again", 1L, caught.heard?.id)
        assertTrue(caught.replies.isEmpty())
        assertEquals(null, caught.selectedReply)
        assertEquals(2L, caught.lastWearerTurnId)
        assertEquals("miss WEARER_LINE 1.000 (next 0.000, local 0.000) 「一位。」", caught.playbookNote)
        assertEquals("the notice outlasts the turn", "Caught your forgotten tap. Tap before speaking next time.", caught.statusLine)
        assertEquals("no writing for my line", listOf(RequestTag.THEIRS, RequestTag.STAGE_B, RequestTag.THEIRS), model.requests.map { it.tag })

        // Their next line's Stage B prompt has my answer as mine, not as theirs.
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "嗯，几位", "How many?", ZH_REPLIES))
        processor.onInput(ended(3, Speaker.THEM)); advanceUntilIdle()
        val stageB = model.requests.last()
        assertEquals(RequestTag.STAGE_B, stageB.tag)
        assertTrue(stageB.text, stageB.text.contains("THEM: 嗯，几位\nME: 一位"))
        assertFalse(stageB.text, stageB.text.contains("THEM: 一位"))
        assertEquals("their next turn ends the notice", null, processor.hud.value.statusLine)
    }

    @Test fun `right after my own line, their echo of my seeded answer stays their line, for Stage B as before`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val (model, processor) = warmed(PipelineConfig(playbookScene = "restaurant")) { index }
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "嗯，几位", "How many?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        model.enqueue(FakeSpeechModel.ScriptedTurn("me", "zh", "一位", "Just one.", roman = "Yí wèi"))
        processor.onInput(ended(2, Speaker.ME)); advanceUntilIdle()
        assertEquals("ME_L2", processor.hud.value.trace?.shape)
        // They say my answer back, untapped: as near my seeded answer as my own reading, but the turn is theirs.
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "一位", "Just one.", roman = "Yí wèi"))
        processor.onInput(ended(3, Speaker.THEM)); advanceUntilIdle()
        val echoed = processor.hud.value
        assertEquals("THEM_TWO_STAGE", echoed.trace?.shape)
        assertEquals(Line("一位", "zh-CN", "Just one.", 3, Speaker.THEM, "Yí wèi"), echoed.heard)
        assertEquals(null, echoed.wearer)
        assertEquals("the partner cue still waits on my line", 2L, echoed.lastWearerTurnId)
        assertEquals("miss WEARER_LINE 1.000 (next 0.000, local 0.000) 「一位。」", echoed.playbookNote)
        assertEquals(ReplySource.MODEL, echoed.replySource)
        assertTrue(echoed.replies.isNotEmpty())
        assertEquals(listOf(RequestTag.THEIRS, RequestTag.STAGE_B, RequestTag.YOURS, RequestTag.THEIRS, RequestTag.STAGE_B), model.requests.map { it.tag })
    }

    @Test fun `prepared replies leave no exemplars from the last written ones on the bench`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val hashing = HashingEmbedder()
        val store = InMemoryExemplarStore().apply { add("Just me, thanks.", "en", Source.TRANSCRIPT, hashing.embed("Just me, thanks.")) }
        val (model, processor) = warmed(PipelineConfig(playbookScene = "restaurant"), Retriever(store, hashing)) { index }
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "嗯，几位", "How many?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        assertEquals(listOf("Just me, thanks."), processor.hud.value.exemplarsUsed.map { it.text })
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "您好，几位", "Hello, how many?", ZH_REPLIES))
        processor.onInput(ended(2, Speaker.THEM)); advanceUntilIdle()
        assertEquals(ReplySource.PLAYBOOK, processor.hud.value.replySource)
        assertEquals(emptyList<String>(), processor.hud.value.exemplarsUsed.map { it.text })
    }

    @Test fun `a weaker reading of my seeded answer stays their line, for Stage B as before`() = runTest {
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val on = PipelineConfig(playbookScene = "restaurant")
        for ((heard, cfg, note) in listOf(
            Triple("嗯，一位", on, "miss WEARER_LINE 0.600 (next 0.000, local 0.000) 「一位。」"), // under the threshold
            Triple("您好，一位", on.copy(playbookThreshold = 0.70f), "miss WEARER_LINE 0.707 (next 0.000, local 0.707) 「一位。」"), // as near their greeting
            Triple("一位", on.copy(catchForgottenTaps = false), "miss WEARER_LINE 1.000 (next 0.000, local 0.000) 「一位。」"), // catching taps is off
        )) {
            val (written, requests) = theirTurn(heard, cfg) { index }
            assertEquals(note, written.playbookNote)
            assertEquals(heard, theirTurn(heard, cfg.copy(playbookScene = "")), written.copy(playbookNote = null) to requests)
            assertEquals(Speaker.THEM, written.heard?.speaker)
            assertEquals(1, requests.count { it.tag == RequestTag.STAGE_B })
        }
    }

    @Test fun `a playbook match that fails is a miss`() = runTest {
        // The fixed embedder has no vector for this line, so embedding it throws.
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), FixedEmbedder())
        val (failed, requests) = theirTurn("几点关门？", PipelineConfig(playbookScene = "restaurant")) { index }
        assertEquals("match failed: no vector for \"几点关门？\"", failed.playbookNote)
        assertEquals(theirTurn("几点关门？", PipelineConfig()), failed.copy(playbookNote = null) to requests)
    }

    @Test fun `a pair other than the playbook's skips it`() = runTest {
        val embedder = FixedEmbedder()
        val index = PlaybookIndex.build(Playbook.parse(PLAYBOOK), embedder)
        for (cfg in listOf(PipelineConfig(l1 = Language.EN_GB, playbookScene = "restaurant"), PipelineConfig(l2 = Language.ZH_TW, playbookScene = "restaurant"))) {
            val skipped = theirTurn("您好，几位", cfg) { index }
            assertEquals(theirTurn("您好，几位", cfg.copy(playbookScene = "")), skipped)
            assertEquals(null, skipped.first.playbookNote)
        }
        assertTrue(embedder.queries.isEmpty())
    }

    /** Fixed vectors for [PLAYBOOK]'s lines and the transcripts above, so every score is exact. Records each query. */
    private class FixedEmbedder : TextEmbedder {
        override val dims = 5
        override val description = "fixed"
        val queries = ArrayList<String>()

        override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
            if (role == EmbedRole.QUERY) queries += texts
            return texts.map { VECTORS[it] ?: error("no vector for \"$it\"") }
        }
    }

    /** A warmed processor on the fake model, at a fixed time so that two runs' traces are equal. */
    private suspend fun TestScope.warmed(cfg: PipelineConfig, retriever: Retriever? = null, playbook: () -> PlaybookIndex? = { null }): Pair<FakeSpeechModel, CompanionProcessor> {
        val model = FakeSpeechModel(stageADelayMs = 0, stageBDelayMs = 0)
        val processor = CompanionProcessor(model, MutableStateFlow(cfg), this, retriever, clock = { 0L }, playbook = playbook)
        processor.warm()
        return model to processor
    }

    /** Their line [heard] on a fresh processor: the HUD after it, less its random session id, and the requests, less the audio. */
    private suspend fun TestScope.theirTurn(heard: String, cfg: PipelineConfig, playbook: () -> PlaybookIndex? = { null }): Pair<HudState, List<GenerateRequest>> {
        val (model, processor) = warmed(cfg, playbook = playbook)
        model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", heard, "How many?", ZH_REPLIES))
        processor.onInput(ended(1, Speaker.THEM)); advanceUntilIdle()
        return processor.hud.value.copy(sessionId = "") to model.requests.map { it.copy(audio = null) }
    }

    private fun audio() = AudioTurn(FloatArray(16_000), 0, 1000, 16_000)
    companion object {
        const val REPLIES = """{"replies":[{"plain":"Un café, por favor.","yours":"Un café con leche, por favor. Gracias.","roman":null,"back":"A coffee with milk, please. Thanks."},{"plain":"Agua, gracias.","yours":"Para mí, solo agua. Gracias.","roman":null,"back":"Just water for me, thanks."}]}"""
        private const val ZH_REPLIES = """{"replies":[{"plain":"两位。","yours":"我们两个人。","roman":"Wǒmen liǎng gè rén.","back":"The two of us."}]}"""

        /** The restaurant's two moments, so a hit has a runner-up, and a café of one, for every scene at once. */
        private val PLAYBOOK = """
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
                 "others": []}]},
              {"id": "cafe", "title": "Café", "setting": "A coffee counter", "localRole": "barista", "start": "cafe.order", "moments": [
                {"id": "cafe.order", "intent": "asks what to drink",
                 "local": [{"id": "cafe.order.l1", "text": "您想喝点什么？", "l1": "What would you like to drink?"}],
                 "replies": [{"id": "cafe.order.r1", "text": "一杯拿铁。", "roman": "Yì bēi nátiě.", "back": "A latte.", "next": ""}],
                 "others": []}]}]}
        """.trimIndent()

        // Axes: greeting, food, drink, the wearer's answers, unrelated. A score is the dot product at unit length.
        private val VECTORS = mapOf(
            "您好，几位？" to floatArrayOf(1f, 0f, 0f, 0f, 0f),
            "您想吃点什么？" to floatArrayOf(0f, 1f, 0f, 0f, 0f),
            "您想喝点什么？" to floatArrayOf(0f, 0.6f, 0.8f, 0f, 0f), // 0.6 from the food question: both ask for an order
            "一位。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "两位。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "来一个宫保鸡丁。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "一杯拿铁。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            // Their transcripts.
            "您好，几位" to floatArrayOf(1f, 0f, 0f, 0f, 0f),
            "嗯，几位" to floatArrayOf(0.6f, 0f, 0f, 0f, 0.8f), // 0.6 from the greeting: under the default threshold
            "您想喝点什么" to floatArrayOf(0f, 0.6f, 0.8f, 0f, 0f), // the café's question: in the restaurant, 0.6 from the food one
            // My answers, said without a tap.
            "一位" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "嗯，一位" to floatArrayOf(0f, 0f, 0f, 0.6f, 0.8f), // 0.6 from my answers: under the default threshold
            "您好，一位" to floatArrayOf(1f, 0f, 0f, 1f, 0f), // as near their greeting as my answers, 0.707 each
        )
    }
}

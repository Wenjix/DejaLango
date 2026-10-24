package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.conversation.ConversationState
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.ModelInfo
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.TokenChunk
import dev.dejalango.engine.llm.generateText
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.trace.TurnTrace
import dev.dejalango.engine.turn.EndReason
import dev.dejalango.engine.turn.TurnEvent
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.HashingEmbedder
import dev.dejalango.engine.voice.InMemoryExemplarStore
import dev.dejalango.engine.voice.Retriever
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.VoiceCard
import dev.dejalango.engine.voice.VoiceCardBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnProcessorTest {

    private class Rig(config: PipelineConfig = PipelineConfig(), stageA: Long = 60, stageB: Long = 80) {
        val model = FakeSpeechModel(stageADelayMs = stageA, stageBDelayMs = stageB, l1 = config.l1.code, l2 = config.l2.code)
        val store = InMemoryExemplarStore()
        val embedder = HashingEmbedder()
        val retriever = Retriever(store, embedder)
        val conversation = ConversationState()
        val cfg = MutableStateFlow(config)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val traces = ArrayList<TurnTrace>()
        val processor = TurnProcessor(model, retriever, conversation, PromptBuilder(), cfg, scope)

        init {
            scope.launch { processor.traces.collect { traces += it } }
        }

        suspend fun seed() {
            for (s in listOf("So basically we should just ship it and see.", "No worries if it breaks.", "Honestly I think that's fine."))
                store.add(s, "en", Source.TRANSCRIPT, embedder.embed(s))
        }

        fun turn(ms: Long = 1500): AudioTurn = AudioTurn(FloatArray((16_000L * ms / 1000).toInt()), 0, ms, 16_000)

        suspend fun awaitPhase(phase: Phase, timeoutMs: Long = 5_000): HudState =
            withTimeout(timeoutMs) { processor.hud.first { it.phase == phase } }

        suspend fun awaitTraces(n: Int, timeoutMs: Long = 5_000) {
            withTimeout(timeoutMs) { while (traces.size < n) delay(10) }
        }

        fun close() = scope.cancel()
    }

    @Test
    fun `warm then a THEM turn produces heard and two replies in your voice`() = runBlocking {
        val rig = Rig()
        try {
            rig.seed()
            rig.processor.warm(VoiceCard(l1 = "en", l2 = "zh", signaturePhrases = listOf("just ship it"), confidence = Confidence.FULL))
            assertEquals(Phase.IDLE, rig.processor.hud.value.phase)
            assertTrue(rig.model.systemPrompt!!.contains("just ship it"))

            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            val s = rig.awaitPhase(Phase.SHOWING)
            assertNotNull(s.heard)
            assertEquals("Is this your first time in Beijing?", s.heard!!.translation)
            assertEquals(2, s.replies.size)
            assertTrue(s.replies[0].yours.isNotEmpty())
            assertNotNull(s.replies[0].roman)
            assertTrue("exemplars should be shown on the bench", s.exemplarsUsed.isNotEmpty())

            rig.awaitTraces(1)
            val t = rig.traces[0]
            assertNotNull(t.tAEnd); assertNotNull(t.tBEnd); assertNotNull(t.tRetrieved)
            assertFalse(t.cancelled); assertFalse(t.parseFailed); assertFalse(t.timedOut)
            assertEquals("TWO_STAGE", t.shape)

            // The stage-B prompt carried the retrieved exemplars; the card's signature phrase rides in the system prompt.
            val stageB = rig.model.requests.first { it.tag == RequestTag.STAGE_B }
            assertTrue(stageB.text.contains("So basically we should just ship it and see."))
            assertTrue(rig.model.requests.first { it.tag == RequestTag.STAGE_A }.audio != null)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `prepare runs after each finished turn and schemas ride along when structured output is on`() = runBlocking {
        val rig = Rig(PipelineConfig(structuredOutput = true))
        try {
            rig.processor.warm(null)
            val afterWarm = withTimeout(2_000) { while (rig.model.prepareCalls < 1) delay(10); rig.model.prepareCalls }
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            withTimeout(2_000) { while (rig.model.prepareCalls < afterWarm + 1) delay(10) } // idle again: prepare
            assertTrue(rig.model.requests.all { it.jsonSchema != null })
            assertTrue(rig.model.requests.first { it.tag == RequestTag.STAGE_B }.jsonSchema!!.contains("\"replies\""))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `my turn that matches a suggestion counts as learned`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // THEM (scripted default #1)
            rig.awaitPhase(Phase.SHOWING)
            rig.awaitTraces(1)
            // Even when Stage A labels Mandarin speech as "them", reading a suggested reply aloud is recognized as ME:
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            val s = rig.processor.hud.value
            assertEquals(1, s.learnedToday)
            assertEquals(0, s.selectedReply)
            assertEquals("你是第一次来北京吗？", s.heard?.text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `reply options records unmatched ME lines without replacing the cards`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitPhase(Phase.SHOWING)
            rig.awaitTraces(1)
            val replies = rig.processor.hud.value.replies

            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)

            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(replies, rig.processor.hud.value.replies)
            assertEquals("their line stays above the cards that answer it", "你是第一次来北京吗？", rig.processor.hud.value.heard?.text)
            assertEquals("your turn: read a reply aloud (or tap it + 'I said this one')", rig.processor.hud.value.statusLine)
            assertEquals(listOf("THEM", "ME"), rig.conversation.snapshot().map { it.speaker.name })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `their Mandarin gets replies even when stage A tags it as mine`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            // What Gemma answers on the phone for the other person's line: the right language, the wrong speaker.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "zh", "你是第一次来北京吗？", "Is this your first time in Beijing?"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)

            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(2, rig.processor.hud.value.replies.size)
            assertEquals(listOf("THEM"), rig.conversation.snapshot().map { it.speaker.name })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `my English stays an aside even when stage A tags it as theirs`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)

            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "en-US", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)

            assertEquals("no replies to my own words", 1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(listOf("THEM", "ME"), rig.conversation.snapshot().map { it.speaker.name })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `who spoke follows stage A's language in any common spelling`() {
        val cfg = PipelineConfig() // I speak English, they speak Mandarin
        for (lang in listOf("en", "EN", "en-US", "en_GB", "eng", "English", " english ")) assertEquals(lang, Speaker.ME, TurnProcessor.speakerOf(lang, cfg))
        // Their language, another one, or none: the prompt's "if unclear, them".
        for (lang in listOf("zh", "zh-Hans", "zh_TW", "zho", "cmn", "Chinese", "es", "")) assertEquals(lang, Speaker.THEM, TurnProcessor.speakerOf(lang, cfg))

        val mandarinWearer = PipelineConfig(l1 = Language.ZH, l2 = Language.EN)
        for (lang in listOf("zh", "zh-Hans", "zh_TW", "zho", "Mandarin", "Chinese")) assertEquals(lang, Speaker.ME, TurnProcessor.speakerOf(lang, mandarinWearer))
        assertEquals(Speaker.THEM, TurnProcessor.speakerOf("en", mandarinWearer))
    }

    @Test
    fun `an aside after a matched reply keeps their line and the spent cards`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(0, rig.processor.hud.value.selectedReply)

            // An aside while the used cards are still up: their line stays, and the status waits for their response.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(3)
            val s = rig.processor.hud.value
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(shown, s.replies)
            assertEquals(0, s.selectedReply)
            assertEquals("你是第一次来北京吗？", s.heard?.text)
            assertEquals("listening for their response…", s.statusLine)
            assertEquals(listOf("THEM", "ME", "ME"), rig.conversation.snapshot().map { it.speaker.name })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an aside after a confirmed reply waits for their response`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            // Not my reading of the confirmed reply, so an aside, while the used cards are still up.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(listOf("THEM", "ME", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertEquals("listening for their response…", rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a reply not on offer prompts no turn and cannot be confirmed`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            // Stage B answers in English prose, not JSON: the raw fallback card shows, but it is not in L2, so not on offer.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "一起去喝咖啡吧", "Let's go for coffee together", "Sure! You could say you'd love to come along."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(listOf("Sure! You could say you'd love to come along."), rig.processor.hud.value.replies.map { it.yours })
            assertNull("nothing to read aloud or tap", rig.processor.hud.value.statusLine)

            // Tapping the card and "I said this one" counts nothing, records nothing and changes nothing.
            rig.processor.onAction(HudAction.Select(0))
            val selected = rig.processor.hud.value
            rig.processor.onAction(HudAction.Confirm)
            assertEquals(0, rig.processor.hud.value.learnedToday)
            assertEquals(selected, rig.processor.hud.value)
            assertEquals(listOf("THEM"), rig.conversation.snapshot().map { it.speaker.name })

            // An aside: no card was used, so the status does not wait for their response either.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(listOf("THEM", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertNull(rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a card used from the earlier set does not make an aside wait for their response`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            // Their next line gets only the raw fallback card, which is not on offer.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "一起去喝咖啡吧", "Let's go for coffee together", "Sure! You could say you'd love to come along."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(listOf("Sure! You could say you'd love to come along."), rig.processor.hud.value.replies.map { it.yours })
            assertNull(rig.processor.hud.value.statusLine)

            // The confirmed card was in the old set, so an aside now does not wait for their response.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(3)
            assertNull(rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `say it my way only generates replies for ME lines`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(0, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertTrue(rig.processor.hud.value.replies.isEmpty())

            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitPhase(Phase.SHOWING)
            rig.awaitTraces(2)
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertTrue(rig.processor.hud.value.replies.isNotEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `in say it my way, reading a variant aloud counts as mine and as learned`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertTrue(shown.isNotEmpty())
            val used: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
            rig.scope.launch { rig.processor.usedReplies.collect { used += it } }
            delay(20)

            // Stage A labels my Mandarin "them": reading variant #1 aloud is still my line, not theirs.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", shown[0].yours, "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            val s = rig.processor.hud.value
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(listOf("ME", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertEquals(1, s.learnedToday)
            assertEquals(0, s.selectedReply)
            assertEquals("the variants stay up after a match", shown, s.replies)
            withTimeout(2_000) { while (used.isEmpty()) delay(10) }
            assertEquals("the app embeds the variant I read, once", listOf(shown[0].yours), used.toList())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `in say it my way, reading a confirmed variant aloud is absorbed`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            // Reading the confirmed variant aloud (labeled "them") is the line Confirm already recorded and counted.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", shown[0].yours, "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            val s = rig.processor.hud.value
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(listOf("ME", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertEquals(1, s.learnedToday)
            assertEquals(shown, s.replies)
            assertEquals("listening for their response…", s.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a consumed suggestion set never matches again, so their next line gets replies`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // THEM (scripted default #1)
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            // I read reply #1 aloud: matched, learned, no stage B on my own voice.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(1, rig.processor.hud.value.learnedToday)
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals("the cards stay up after a match", shown, rig.processor.hud.value.replies)
            assertTrue(rig.conversation.lastSuggested.isEmpty())

            // The same words again, while those cards are still rendered: the set is spent, so this is a new line.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(3)
            val s = rig.awaitPhase(Phase.SHOWING)
            assertEquals(1, s.learnedToday)
            assertEquals(2, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals("对啊，第一次来，还在到处转悠呢", s.heard?.text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `confirming a reply spends the set once and absorbs my reading of it`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)
            rig.processor.onAction(HudAction.Confirm)
            assertEquals("a second confirm of the same set is not counted", 1, rig.processor.hud.value.learnedToday)

            // I then say the confirmed reply: it is that same reply, so it is neither counted again nor answered.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(1, rig.processor.hud.value.learnedToday)
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals("你是第一次来北京吗？", rig.processor.hud.value.heard?.text)
            assertEquals(listOf("THEM", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertEquals("listening for their response…", rig.processor.hud.value.statusLine)

            // Their next line gets replies, and the echo was one-shot: saying that reply again now is a new line.
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你觉得这里的菜怎么样？
            rig.awaitTraces(3)
            assertEquals(2, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(4)
            assertEquals(3, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `stage A hears only the turn and stage B states the current line once`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你觉得这里的菜怎么样？
            rig.awaitTraces(2)

            val stageA = rig.model.requests.filter { it.tag == RequestTag.STAGE_A }[1].text
            assertFalse("no earlier lines in stage A", stageA.contains("北京"))
            shown.forEach { r -> assertFalse("no suggested replies in stage A", stageA.contains(r.yours) || stageA.contains(r.plain)) }

            val stageB = rig.model.requests.filter { it.tag == RequestTag.STAGE_B }[1].text
            assertTrue("earlier line as context", stageB.contains("你是第一次来北京吗？"))
            assertEquals("current line exactly once", 1, Regex("你觉得这里的菜怎么样").findAll(stageB).count())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a reply written in L1 falls back to the phrasebook line`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            // Seen on device: the card's English signature move copied verbatim into "yours".
            val replies = """{"replies":[{"plain":"好啊，听起来不错。","yours":"sounds good to me","roman":"sounds good to me","back":"Sounds good to me"},""" +
                """{"plain":"去哪儿？","yours":"去哪儿呀？","roman":"Qù nǎr ya?","back":"Where to?"}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "一起去喝咖啡吧", "Let's go for coffee together", replies))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            assertEquals(listOf("好啊，听起来不错。", "去哪儿呀？"), shown.map { it.yours })
            assertNull("the romanization was of the English line", shown[0].roman)
            assertNull("the back-translation was of the discarded English line", shown[0].back)
            assertEquals("Qù nǎr ya?", shown[1].roman)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `confirming a card with an L1 phrasebook line records its L2 personal line`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            val yours = "我想先想一下。"
            val replies = """{"replies":[{"plain":"I need a moment to think.","yours":"$yours","roman":"Wǒ xiǎng xiān xiǎng yíxià.","back":"I'd like a moment to think."}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "你需要点什么吗？", "Do you need anything?", replies))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(yours, rig.processor.hud.value.replies.single().plain)

            rig.processor.onAction(HudAction.TogglePlain)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            assertEquals(yours, rig.conversation.snapshot().last().text)
            assertEquals(1, rig.processor.hud.value.learnedToday)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `confirming a phrasebook line that differs from yours records it with no translation`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            val plain = "我想要一杯水。"
            val replies = """{"replies":[{"plain":"$plain","yours":"来杯水就行。","roman":"Lái bēi shuǐ jiù xíng.","back":"A glass of water will do."}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "你需要点什么吗？", "Do you need anything?", replies))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(plain, rig.processor.hud.value.replies.single().plain)

            rig.processor.onAction(HudAction.TogglePlain)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            val mine = rig.conversation.snapshot().last()
            assertEquals(plain, mine.text)
            assertNull("the card's back-translation is of yours, another sentence", mine.textL1)
            assertEquals(1, rig.processor.hud.value.learnedToday)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `confirming a personal line records its back-translation`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            val yours = "来杯水就行。"
            val back = "A glass of water will do."
            val replies = """{"replies":[{"plain":"我想要一杯水。","yours":"$yours","roman":"Lái bēi shuǐ jiù xíng.","back":"$back"}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "你需要点什么吗？", "Do you need anything?", replies))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)

            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)

            val mine = rig.conversation.snapshot().last()
            assertEquals(yours, mine.text)
            assertEquals(back, mine.textL1)
            assertEquals(1, rig.processor.hud.value.learnedToday)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `in say it my way, a raw variant in my own language is shown but not matched`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            // Stage B answers in plain text that echoes my line, so the raw fallback card is English.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(listOf("Yeah, first time."), rig.processor.hud.value.replies.map { it.yours })

            // Saying my line again asks for variants again: it is not a reply read aloud.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(2, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(0, rig.processor.hud.value.learnedToday)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `in say it my way, my line copied into a variant's phrasebook line is not matched`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            // A 2B model copies my English line into "plain"; only "yours" is Mandarin.
            val variants = """{"replies":[{"plain":"Yeah, first time.","yours":"对啊，第一次来。","roman":"Duì a, dì yī cì lái.","back":"Yeah, first time."}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time.", variants))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(listOf("对啊，第一次来。"), rig.processor.hud.value.replies.map { it.yours })
            assertEquals(listOf("对啊，第一次来。"), rig.processor.hud.value.replies.map { it.plain })
            val used: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
            rig.scope.launch { rig.processor.usedReplies.collect { used += it } }
            delay(20)

            // Saying my line again asks for variants again: it is not the variant read aloud.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(2, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(0, rig.processor.hud.value.learnedToday)
            delay(100)
            assertTrue("no reply goes to the app: $used", used.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `in say it my way, my line copied into a confirmed variant is not its echo`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY))
        try {
            rig.processor.warm(null)
            val variants = """{"replies":[{"plain":"Yeah, first time.","yours":"对啊，第一次来。","roman":"Duì a, dì yī cì lái.","back":"Yeah, first time."}]}"""
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time.", variants))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals("对啊，第一次来。", rig.processor.hud.value.replies.single().plain)
            rig.processor.onAction(HudAction.Select(0))
            rig.processor.onAction(HudAction.Confirm)
            assertEquals(1, rig.processor.hud.value.learnedToday)

            // The echo to absorb is my reading of the Mandarin variant; my English line again asks for variants again.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertEquals(2, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertEquals(1, rig.processor.hud.value.learnedToday)
        } finally {
            rig.close()
        }
    }

    /** LiteRT-LM's native prefill runs to completion even when its caller is cancelled; this model does the same. */
    private class UninterruptibleModel(private val prefillMs: Long) : SpeechLanguageModel {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        override val info = ModelInfo(name = "uninterruptible", backend = "fake")
        override suspend fun warm(systemPrompt: String) = Unit
        override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                withContext(NonCancellable) { delay(prefillMs) }
                emit(TokenChunk(if (req.tag == RequestTag.STAGE_A) """{"speaker":"them","lang":"zh","heard":"你好","heard_l1":"Hello"}""" else """{"replies":[{"plain":"你好","yours":"你好呀","roman":"Nǐ hǎo ya","back":"Hi there"}]}"""))
                emit(TokenChunk("", done = true))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Like LiteRT-LM creating a spare conversation: each unit of idle prep blocks its thread in a native call. */
    private class PreparingModel(private val unitMs: Long, private val units: Int, private val genMs: Long = 50) : SpeechLanguageModel {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val prepared = AtomicInteger()
        val unitsStarted = AtomicInteger()
        val tags: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val events: MutableList<String> = java.util.Collections.synchronizedList(ArrayList()) // "prep" or a request tag, in model order
        @Volatile var unitsAtFirstGenerate = -1
        @Volatile var lastUpcoming: List<String> = emptyList()
        @Volatile var lastStructured: Boolean? = null
        override val info = ModelInfo(name = "preparing", backend = "fake")
        override suspend fun warm(systemPrompt: String) = Unit
        override suspend fun prepare(upcoming: List<String>, structured: Boolean): Boolean = busy {
            lastStructured = structured
            lastUpcoming = upcoming
            events += "prep"
            unitsStarted.incrementAndGet()
            Thread.sleep(unitMs)
            prepared.incrementAndGet() < units
        }
        override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
            if (unitsAtFirstGenerate < 0) unitsAtFirstGenerate = unitsStarted.get()
            tags += req.tag
            events += req.tag
            val text = busy {
                withContext(NonCancellable) { delay(genMs) }
                if (req.tag == RequestTag.STAGE_A) """{"speaker":"them","lang":"zh","heard":"你好","heard_l1":"Hello"}""" else """{"replies":[{"plain":"你好","yours":"你好呀","roman":"Nǐ hǎo ya","back":"Hi there"}]}"""
            }
            emit(TokenChunk(text))
            emit(TokenChunk("", done = true))
        }
        private suspend fun <T> busy(block: suspend () -> T): T {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** The app's setup: one model thread, which a prep unit blocks. */
    private class OneThread {
        val llm = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(llm + SupervisorJob())
        fun close() {
            scope.cancel()
            llm.close()
        }
    }

    @Test
    fun `idle prep runs one unit at a time and gives way to a turn`() = runBlocking {
        val model = PreparingModel(unitMs = 300, units = 4)
        val t = OneThread()
        val processor = TurnProcessor(model, null, ConversationState(), PromptBuilder(), MutableStateFlow(PipelineConfig()), t.scope)
        val traces = ArrayList<TurnTrace>()
        launch(Dispatchers.Default) { processor.traces.collect { synchronized(traces) { traces += it } } }.also { delay(20) }
        try {
            withContext(t.llm) { processor.warm(null) } // schedules four 300 ms units of prep
            delay(100)
            processor.onEvent(TurnEvent.TurnEnded(AudioTurn(FloatArray(16_000), 0, 1_000, 16_000), EndReason.SILENCE))
            withTimeout(5_000) { while (synchronized(traces) { traces.size } < 1) delay(10) }
            assertEquals("no unit starts after the turn ended", 1, model.unitsAtFirstGenerate)
            assertEquals(listOf(RequestTag.STAGE_A, RequestTag.STAGE_B), model.lastUpcoming)
            withTimeout(5_000) { while (model.prepared.get() < 4) delay(10) } // idle again: prep picks up where it stopped
            assertEquals("prep and generations never overlap", 1, model.maxInFlight.get())
        } finally {
            t.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `resumed speech cancels a turn still waiting behind prep`() = runBlocking {
        val model = PreparingModel(unitMs = 400, units = 2)
        val t = OneThread()
        val processor = TurnProcessor(model, null, ConversationState(), PromptBuilder(), MutableStateFlow(PipelineConfig()), t.scope)
        try {
            withContext(t.llm) { processor.warm(null) }
            delay(100) // a prep unit holds the model thread
            processor.onEvent(TurnEvent.TurnEnded(AudioTurn(FloatArray(16_000), 0, 1_000, 16_000), EndReason.SILENCE))
            delay(50)
            processor.onEvent(TurnEvent.SpeechResumed(tMs = 1_300, afterTurnEndedAtMs = 1_000))
            withTimeout(5_000) { while (model.prepared.get() < 2) delay(10) } // prep re-armed for the idle gap
            delay(200)
            assertTrue("the superseded fragment never reached the model: ${model.tags}", model.tags.isEmpty())
        } finally {
            t.close()
        }
    }

    @Test
    fun `switching mode or structured output prepares for the new requests`() = runBlocking {
        val model = PreparingModel(unitMs = 10, units = 1)
        val t = OneThread()
        val config = MutableStateFlow(PipelineConfig(structuredOutput = false))
        val processor = TurnProcessor(model, null, ConversationState(), PromptBuilder(), config, t.scope)
        try {
            withContext(t.llm) { processor.warm(null) }
            withTimeout(2_000) { while (model.prepared.get() < 1) delay(10) }
            assertEquals(listOf(RequestTag.STAGE_A, RequestTag.STAGE_B), model.lastUpcoming)
            assertEquals(false, model.lastStructured)
            config.value = config.value.copy(mode = Mode.WORD_FINDING, structuredOutput = true)
            processor.onConfigChanged(config.value)
            withTimeout(2_000) { while (model.lastUpcoming != listOf(RequestTag.WORD) || model.lastStructured != true) delay(10) }
        } finally {
            t.close()
        }
    }

    @Test
    fun `a settings change never runs prep ahead of a turn waiting for the model`() = runBlocking {
        val model = PreparingModel(unitMs = 100, units = 1, genMs = 300)
        val t = OneThread()
        val config = MutableStateFlow(PipelineConfig())
        val processor = TurnProcessor(model, null, ConversationState(), PromptBuilder(), config, t.scope)
        try {
            withContext(t.llm) { processor.warm(null) }
            withTimeout(2_000) { while (model.prepared.get() < 1) delay(10) }
            processor.onEvent(TurnEvent.TurnEnded(AudioTurn(FloatArray(16_000), 0, 1_000, 16_000), EndReason.SILENCE))
            withTimeout(2_000) { while (model.tags.isEmpty()) delay(5) } // in its stage A, which a cancel can't interrupt
            processor.onEvent(TurnEvent.TurnEnded(AudioTurn(FloatArray(16_000), 2_000, 3_000, 16_000), EndReason.SILENCE)) // joins the first
            processor.onConfigChanged(config.value)
            withTimeout(5_000) { while (model.tags.size < 3) delay(10) }
            assertEquals("the waiting turn goes next: ${model.events}", listOf("prep", RequestTag.STAGE_A, RequestTag.STAGE_A), model.events.take(3))
        } finally {
            t.close()
        }
    }

    @Test
    fun `clearing the hud mid-turn still refills the spares`() = runBlocking {
        val rig = Rig(stageA = 400)
        try {
            rig.processor.warm(null)
            withTimeout(2_000) { while (rig.model.prepareCalls < 1) delay(10) }
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitPhase(Phase.THINKING)
            val before = rig.model.prepareCalls
            rig.processor.onAction(HudAction.Clear) // cancels the turn; no other turn follows
            withTimeout(2_000) { while (rig.model.prepareCalls <= before) delay(10) }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `clearing the hud during stage B does not leave the generating status up`() = runBlocking {
        val rig = Rig(stageB = 400)
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            withTimeout(5_000) { rig.processor.hud.first { it.statusLine == "generating replies…" } }
            rig.processor.onAction(HudAction.Clear) // cancels the turn in stage B
            rig.awaitTraces(1)
            assertTrue(rig.traces[0].cancelled)
            assertNotEquals("generating replies…", rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `clearing the hud while cards are up clears the status`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals("your turn: read a reply aloud (or tap it + 'I said this one')", rig.processor.hud.value.statusLine)
            rig.processor.onAction(HudAction.Clear)
            assertTrue(rig.processor.hud.value.replies.isEmpty())
            assertNull("no prompt over the emptied hud", rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `turns never share the model, even after a resume cancel or a pile-up`() = runBlocking {
        val model = UninterruptibleModel(prefillMs = 200)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processor = TurnProcessor(model, null, ConversationState(), PromptBuilder(), MutableStateFlow(PipelineConfig()), scope)
        val traces = ArrayList<TurnTrace>()
        scope.launch { processor.traces.collect { synchronized(traces) { traces += it } } }
        val turn = AudioTurn(FloatArray(16_000), 0, 1_000, 16_000)
        try {
            processor.warm(null)
            processor.onEvent(TurnEvent.TurnEnded(turn, EndReason.SILENCE))
            delay(50)
            // Speech resumed during stage A: cancelled, but still in prefill when the continuation ends.
            processor.onEvent(TurnEvent.SpeechResumed(tMs = 1_100, afterTurnEndedAtMs = 1_000))
            processor.onEvent(TurnEvent.TurnEnded(turn, EndReason.SILENCE))
            delay(20)
            // A third turn supersedes the second while that one is still waiting for the first.
            processor.onEvent(TurnEvent.TurnEnded(turn, EndReason.SILENCE))
            // The owner's own model calls (voice-card builds) queue behind turns too.
            val owner = scope.launch { processor.ownerModel.generateText(GenerateRequest(text = "card", tag = RequestTag.VOICE_CARD)) }
            withTimeout(5_000) { while (synchronized(traces) { traces.size } < 2) delay(10) }
            owner.join()
            assertEquals("one generation on the model at a time", 1, model.maxInFlight.get())
            synchronized(traces) {
                assertTrue(traces[0].cancelled)
                assertTrue(traces[1].tBEnd != null && !traces[1].cancelled)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `garbage output degrades to a raw reply, never a blank hud`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.model.failNext = true
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            val s = rig.processor.hud.value
            assertEquals(Phase.SHOWING, s.phase)
            assertNotNull(s.heard)
            assertTrue(s.heard!!.text.startsWith("Sure!"))
            assertTrue(rig.traces[0].parseFailed)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an empty transcript does not leave the transcribing status up`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", "", ""))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertEquals(0, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
            assertNotEquals("transcribing speech…", rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a transcript copied from the stage A prompt ends the turn like an empty one`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            // The second copy is verbatim: the prompt's "me"|"them" is not JSON.
            for ((i, speaker) in listOf("them", "me\"|\"them").withIndex()) {
                rig.model.enqueue(FakeSpeechModel.ScriptedTurn(speaker, "<language code>", "<verbatim transcript in the language spoken (Mandarin Chinese stays Mandarin Chinese), never translated>", "<English translation, or the transcript if already English>"))
                rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
                rig.awaitTraces(i + 1)
                assertEquals(0, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
                assertNull("the skeleton is not their line", rig.processor.hud.value.heard)
                assertNull(rig.processor.hud.value.statusLine)
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a hung model times out and the hud recovers`() = runBlocking<Unit> {
        val rig = Rig(PipelineConfig(generationTimeoutMs = 200))
        try {
            rig.processor.warm(null)
            rig.model.hangNext = true
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertTrue(rig.traces[0].timedOut)
            assertEquals(Phase.IDLE, rig.processor.hud.value.phase)
            assertNotNull(rig.processor.hud.value.error)
            // and the next turn works
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitPhase(Phase.SHOWING)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `timed-out replies do not leave the generating status up`() = runBlocking {
        // Stage A answers in time; stage B's first words would come after the timeout.
        val rig = Rig(PipelineConfig(generationTimeoutMs = 300), stageB = 3_000)
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            assertTrue(rig.traces[0].timedOut)
            val s = rig.processor.hud.value
            assertEquals("replies timed out", s.error)
            assertNotEquals("generating replies…", s.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a newer turn cancels the one in flight`() = runBlocking {
        val rig = Rig(stageA = 400, stageB = 400)
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            delay(50)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            assertTrue(rig.traces.any { it.cancelled })
            assertTrue(rig.traces.any { !it.cancelled && it.tBEnd != null })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `speech resuming during stage A cancels it`() = runBlocking {
        val rig = Rig(stageA = 400, stageB = 100)
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            delay(50)
            rig.processor.onEvent(TurnEvent.SpeechResumed(tMs = 1600, afterTurnEndedAtMs = 1500))
            rig.awaitTraces(1)
            assertTrue(rig.traces[0].cancelled)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `single shape does everything in one call`() = runBlocking {
        val rig = Rig(PipelineConfig(shape = PipelineShape.SINGLE))
        try {
            rig.seed()
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            val s = rig.awaitPhase(Phase.SHOWING)
            assertEquals(2, s.replies.size)
            assertNotNull(s.heard)
            rig.awaitTraces(1)
            assertEquals("SINGLE", rig.traces[0].shape)
            assertEquals(1, rig.model.requests.count { it.tag == RequestTag.SINGLE })
            assertEquals(0, rig.model.requests.count { it.tag == RequestTag.STAGE_B })
        } finally {
            rig.close()
        }
    }

    @Test
    fun `single shape records ME without showing an empty replies card`() = runBlocking {
        val rig = Rig(PipelineConfig(shape = PipelineShape.SINGLE))
        try {
            rig.processor.warm(null)
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "I need a minute.", "I need a minute."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)

            assertTrue(rig.processor.hud.value.replies.isEmpty())
            assertEquals("I need a minute.", rig.processor.hud.value.heard?.text)
            assertNull(rig.processor.hud.value.statusLine)
            assertFalse(rig.traces[0].parseFailed)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `single shape counts a card read aloud as mine and as learned`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.SAY_IT_MY_WAY, shape = PipelineShape.SINGLE))
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE)) // 你是第一次来北京吗？
            rig.awaitTraces(1)
            val shown = rig.processor.hud.value.replies
            assertTrue(shown.isNotEmpty())

            // The single call labels my Mandarin "them" as well: reading card #1 aloud is still my line.
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("them", "zh", shown[0].yours, "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            val s = rig.processor.hud.value
            assertEquals(listOf("THEM", "ME"), rig.conversation.snapshot().map { it.speaker.name })
            assertEquals(1, s.learnedToday)
            assertEquals(0, s.selectedReply)
            assertEquals(shown, s.replies)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `word finding shows a hint above the confidence gate`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.WORD_FINDING))
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.PAUSE))
            val s = rig.awaitPhase(Phase.SHOWING)
            assertEquals("multimeter", s.wordHint)
            assertEquals(listOf("voltmeter", "the meter thing"), s.wordAlternatives)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a word finding turn does not leave the transcribing status up`() = runBlocking {
        val rig = Rig(PipelineConfig(mode = Mode.WORD_FINDING))
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.PAUSE))
            rig.awaitTraces(1)
            assertEquals("multimeter", rig.processor.hud.value.wordHint)
            assertNotEquals("transcribing speech…", rig.processor.hud.value.statusLine)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `hud actions select and confirm replies`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitPhase(Phase.SHOWING)
            rig.processor.onAction(HudAction.Next)
            assertEquals(0, rig.processor.hud.value.selectedReply)
            rig.processor.onAction(HudAction.Next)
            assertEquals(1, rig.processor.hud.value.selectedReply)
            rig.processor.onAction(HudAction.Next)
            assertEquals(0, rig.processor.hud.value.selectedReply)
            var used: String? = null
            val j = rig.scope.launch { used = rig.processor.usedReplies.first() }
            delay(20)
            rig.processor.onAction(HudAction.Confirm)
            j.join()
            assertEquals(rig.processor.hud.value.replies[0].yours, used)
            assertEquals(1, rig.processor.hud.value.learnedToday)
            rig.processor.onAction(HudAction.TogglePlain)
            assertTrue(rig.processor.hud.value.showPlain)
            rig.processor.onAction(HudAction.Clear)
            assertEquals(Phase.IDLE, rig.processor.hud.value.phase)
            assertTrue(rig.processor.hud.value.replies.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `voice card builder parses the fake model's card`() = runBlocking {
        val rig = Rig()
        try {
            val builder = VoiceCardBuilder(rig.model, PromptBuilder())
            val r = builder.fromSentences(PipelineConfig(), listOf("So basically we should ship it.", "No worries if it breaks."), Confidence.FULL)
            assertEquals(Confidence.FULL, r.card.confidence)
            assertEquals(listOf("basically", "honestly"), r.card.fillers)
            assertEquals(4, r.card.directness)
            val q = builder.quickFromAudio(PipelineConfig(), listOf(rig.turn(30_000), rig.turn(30_000)))
            assertEquals(Confidence.QUICK, q.card.confidence)
            assertTrue(q.sentences.size >= 2)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `consecutive fake turns produce distinct transcripts and replies and sayItMyWay works`() = runBlocking {
        val rig = Rig()
        try {
            rig.processor.warm(null)
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(1)
            val firstHeard = rig.processor.hud.value.heard?.text
            val firstReply = rig.processor.hud.value.replies.firstOrNull()?.yours
            assertNotNull(firstHeard)
            assertNotNull(firstReply)

            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.MANUAL))
            rig.awaitTraces(2)
            val secondHeard = rig.processor.hud.value.heard?.text
            val secondReply = rig.processor.hud.value.replies.firstOrNull()?.yours
            assertNotEquals(firstHeard, secondHeard)
            assertNotEquals(firstReply, secondReply)

            rig.cfg.value = PipelineConfig(mode = Mode.SAY_IT_MY_WAY)
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "en", "Yeah, first time.", "Yeah, first time."))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.MANUAL))
            rig.awaitTraces(3)
            assertTrue("say it my way should produce replies in fake mode", rig.processor.hud.value.replies.isNotEmpty())
        } finally {
            rig.close()
        }
    }
}

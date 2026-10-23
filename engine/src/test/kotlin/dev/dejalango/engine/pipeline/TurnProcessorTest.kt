package dev.dejalango.engine.pipeline

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.conversation.ConversationState
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.llm.FakeSpeechModel
import dev.dejalango.engine.llm.RequestTag
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

            // The stage-B prompt carried the retrieved exemplars and the card's signature phrase.
            val stageB = rig.model.requests.first { it.tag == RequestTag.STAGE_B }
            assertTrue(stageB.text.contains("(signature)"))
            assertTrue(stageB.text.contains("ship it"))
            assertTrue(rig.model.requests.first { it.tag == RequestTag.STAGE_A }.audio != null)
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
            rig.model.enqueue(FakeSpeechModel.ScriptedTurn("me", "zh", "对啊，第一次来，还在到处转悠呢", "Yeah, first time, still wandering around"))
            rig.processor.onEvent(TurnEvent.TurnEnded(rig.turn(), EndReason.SILENCE))
            rig.awaitTraces(2)
            val s = rig.processor.hud.value
            assertEquals(1, s.learnedToday)
            assertEquals(0, s.selectedReply)
        } finally {
            rig.close()
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
}

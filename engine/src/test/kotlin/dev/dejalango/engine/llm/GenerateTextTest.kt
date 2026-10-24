package dev.dejalango.engine.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateTextTest {
    /** Streams [pieces] one per chunk and records whether its generation ran to the end or was cancelled. */
    private class Streaming(private val pieces: List<String>) : SpeechLanguageModel {
        override val info = ModelInfo(name = "streaming", backend = "fake", file = null, lastDecodeTokensPerSec = null)
        var emitted = 0
        var finished = false
        var cleanedUp = false
        override suspend fun warm(systemPrompt: String) {}
        override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
            try {
                for (p in pieces) { emit(TokenChunk(p)); emitted++ }
                emit(TokenChunk("", done = true, stats = DecodeStats(outputTokens = pieces.size, decodeTokensPerSec = 20.0)))
                finished = true
            } finally {
                cleanedUp = true // where an adapter cancels its decode
            }
        }
    }

    private val pieces = listOf("""{"lang":"zh",""", """"heard":"你好，""", """你是哪国人啊？"""", ""","heard_l1":"Hi, where""", """ are you from?"}""")

    @Test fun `stopWhen stops collecting, which cancels the generation, and returns the text so far`() = runBlocking {
        val model = Streaming(pieces)
        val gen = model.generateText(GenerateRequest(text = "t"), stopWhen = { it.contains("啊？\"") })
        assertEquals("""{"lang":"zh","heard":"你好，你是哪国人啊？"""", gen.text)
        assertFalse("the generation never reached heard_l1", model.finished)
        assertTrue(model.cleanedUp)
        assertEquals(2, model.emitted) // the third emit was where collection stopped
    }

    @Test fun `without stopWhen the whole answer is collected as before`() = runBlocking {
        val model = Streaming(pieces)
        val gen = model.generateText(GenerateRequest(text = "t"))
        assertEquals(pieces.joinToString(""), gen.text)
        assertTrue(model.finished)
        assertEquals(5, gen.stats?.outputTokens)
    }
}

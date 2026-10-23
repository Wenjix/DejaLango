package dev.dejalango.engine.llm

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import kotlinx.coroutines.flow.Flow

/** Shown on the bench. The Gemma team will ask. */
data class ModelInfo(
    val name: String,
    val backend: String,
    val file: String? = null,
    val lastDecodeTokensPerSec: Double? = null,
)

data class DecodeStats(
    val promptTokens: Int? = null,
    val outputTokens: Int = 0,
    val prefillMs: Long? = null,
    val decodeTokensPerSec: Double? = null,
)

/** Well-known request tags; adapters ignore them, the fake model keys its canned answers on them. */
object RequestTag {
    const val STAGE_A = "stageA"
    const val STAGE_B = "stageB"
    const val SINGLE = "single"
    const val WORD = "word"
    const val TRANSCRIBE = "transcribe"
    const val VOICE_CARD = "voiceCard"
}

data class GenerateRequest(
    /** Audio content goes first in the prompt; adapters are responsible for that ordering. */
    val audio: AudioTurn? = null,
    val text: String,
    val maxTokens: Int = 256,
    val thinking: Boolean = false,
    val tag: String = "",
)

data class TokenChunk(
    val text: String,
    val done: Boolean = false,
    val stats: DecodeStats? = null,
)

/**
 * The only thing the engine knows about Gemma. One instance = one loaded model.
 *
 * Contract: [warm] loads the model and prefills a base session with [systemPrompt]; it is idempotent
 * and may be called again with a new system prompt (new voice card) — one prefill, off the hot path.
 * [generate] runs one request against a clone of the base session, streams tokens, and is cancellable.
 * Calls are serialized by the caller; implementations need not be reentrant.
 */
interface SpeechLanguageModel {
    val info: ModelInfo
    suspend fun warm(systemPrompt: String)
    fun generate(req: GenerateRequest): Flow<TokenChunk>
    fun close() {}
}

class Generated(val text: String, val stats: DecodeStats?, val firstTokenMs: Long?, val doneMs: Long)

/** Collect a streamed generation into text, recording first-token and completion times. */
suspend fun SpeechLanguageModel.generateText(
    req: GenerateRequest,
    clock: Clock = Clock.system(),
    onChunk: ((String) -> Unit)? = null,
): Generated {
    val sb = StringBuilder()
    var first: Long? = null
    var stats: DecodeStats? = null
    generate(req).collect { chunk ->
        if (chunk.text.isNotEmpty()) {
            if (first == null) first = clock.nowMs()
            sb.append(chunk.text)
            onChunk?.invoke(sb.toString())
        }
        if (chunk.stats != null) stats = chunk.stats
    }
    return Generated(sb.toString(), stats, first, clock.nowMs())
}

/** Sentence → vector model info, for the bench. */
data class EmbedderInfo(val name: String, val dims: Int)

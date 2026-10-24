package dev.dejalango.engine.llm

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

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
    const val THEIRS = "transcribeThem"
    const val YOURS = "transcribeYou"
    const val SAY_IT = "sayIt"
    const val HINT = "hint"

    fun usesReplySystem(tag: String): Boolean = tag in setOf(STAGE_B, SINGLE, SAY_IT)
}

data class GenerateRequest(
    /** Audio content goes first in the prompt; adapters are responsible for that ordering. */
    val audio: AudioTurn? = null,
    val text: String,
    val maxTokens: Int = 256,
    val thinking: Boolean = false,
    val tag: String = "",
    /** JSON Schema for constrained decoding, when the runtime supports it (LiteRT-LM ResponseFormat). Null = free text. */
    val jsonSchema: String? = null,
)

data class TokenChunk(
    val text: String,
    val done: Boolean = false,
    val stats: DecodeStats? = null,
)

/**
 * The only thing the engine knows about Gemma. One instance = one loaded model.
 *
 * Contract: [warm] loads the model and sets the base prompt (system instruction) that requests run on;
 * it is idempotent and may be called again with a new system prompt (new voice card).
 * [generate] runs one request on top of that base, streams tokens, and is cancellable. [prepare] is
 * called by the pipeline when idle so the implementation can get the next requests' resources ready
 * (LiteRT-LM creates a conversation per request, slowly with constrained decoding, so the adapter can make the next one early).
 * Calls are serialized by the caller; implementations need not be reentrant.
 */
interface SpeechLanguageModel {
    val info: ModelInfo
    suspend fun warm(systemPrompt: String)
    fun generate(req: GenerateRequest): Flow<TokenChunk>

    /**
     * One unit of idle-time work that makes the next turn's requests faster. [upcoming] lists their [RequestTag]s,
     * in order, and [structured] says whether they carry a [GenerateRequest.jsonSchema]. Returns true when there is
     * more to do. The pipeline calls it between turns, one unit at a time, so a turn that ends meanwhile waits for the
     * unit in progress only. Default: nothing to do.
     */
    suspend fun prepare(upcoming: List<String>, structured: Boolean): Boolean = false
    fun close() {}
}

class Generated(val text: String, val stats: DecodeStats?, val firstTokenMs: Long?, val doneMs: Long)

/**
 * Collect a streamed generation into text, recording first-token and completion times. [stopWhen] sees the text so far
 * after each chunk; true stops collecting, which cancels the generation (adapters cancel their decode on cancellation).
 */
suspend fun SpeechLanguageModel.generateText(
    req: GenerateRequest,
    clock: Clock = Clock.system(),
    stopWhen: ((String) -> Boolean)? = null,
    onChunk: ((String) -> Unit)? = null, // last, so callers' trailing lambdas still bind here
): Generated {
    val sb = StringBuilder()
    var first: Long? = null
    var stats: DecodeStats? = null
    generate(req).firstOrNull { chunk ->
        if (chunk.stats != null) stats = chunk.stats
        if (chunk.text.isEmpty()) return@firstOrNull false
        if (first == null) first = clock.nowMs()
        sb.append(chunk.text)
        val text = sb.toString()
        onChunk?.invoke(text)
        stopWhen?.invoke(text) == true
    }
    return Generated(sb.toString(), stats, first, clock.nowMs())
}

/** Sentence → vector model info, for the bench. */
data class EmbedderInfo(val name: String, val dims: Int)

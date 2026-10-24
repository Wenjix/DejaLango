package dev.dejalango.app.platform.llm.litertlm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LlmBackend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.dejalango.app.platform.ModelFiles
import dev.dejalango.app.platform.audio.Wav
import dev.dejalango.engine.config.AudioBackend
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.llm.DecodeStats
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.ModelInfo
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.TokenChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Gemma 4 E2B/E4B through LiteRT-LM's Kotlin API (`com.google.ai.edge.litertlm`). The only file in the
 * app that imports LiteRT-LM. Written against the API source in google-ai-edge/LiteRT-LM@main
 * (kotlin/java/com/google/ai/edge/litertlm/{Engine,Conversation,Config,Message,ResponseFormat}.kt):
 *
 * - `EngineConfig(modelPath, backend, audioBackend, maxNumTokens, cacheDir)` → `Engine(config).initialize()`
 * - `engine.createConversation(ConversationConfig(systemInstruction = Contents.of(text), prefillPrefaceOnInit = false,
 *    thinkingConfig = ThinkingConfig(enableThinking = false), enableResponseFormat = <the request has a schema>))`
 * - `conversation.sendMessageAsync(Contents.of(Content.AudioBytes(wav), Content.Text(prompt)), maxOutputToken = n,
 *    responseFormat = ResponseFormat.json(schema)): Flow<Message>` — streamed deltas
 * - `conversation.cancelProcess()`, `conversation.close()`, `engine.close()`
 *
 * **Backend ladder.** `Backend.AUTO` tries, in order, every accelerator this phone could have, each with the
 * bundle compiled for it: on a Pixel `GOOGLE_TENSOR` then `NPU` with the `_Google_Tensor_Gx` bundle, then
 * `GPU`/`CPU` with the generic bundle; on a Qualcomm phone `NPU` with the `_qualcomm_smXXXX` bundle, then
 * `GPU`/`CPU`. An explicit backend is tried first with CPU as the only fallback. Every failure is logged with
 * its message and the winner is reported in [info]. TPU/NPU rungs need the vendor runtime in the APK
 * (`-PwithTensorNpu`) and the SoC bundle on the device; without them the rung fails fast and the ladder continues.
 *
 * **Spare conversation.** The Kotlin API has no session cloning, and every request needs a fresh conversation. With
 * `enableResponseFormat`, creating one costs ~2.5 s on the Pixel 11 Pro XL, more than prefilling a whole Stage B
 * prompt: LiteRT-LM builds an llguidance tokenizer over Gemma's whole vocabulary for each conversation. Without it,
 * ~10 ms ([openEngine] creates the engine's first one, which also builds the audio encoder). So only a request with
 * a schema (structured output) gets the response format, and only then does [prepare], which the pipeline calls
 * while idle, create the conversation the next turn's first request needs: plain (Stage A, transcription, voice
 * card) or with the system prompt (single, word). [generate] takes it when the kind fits, and [warm] drops it when
 * the system prompt changes. Only creation happens early: prefill still runs with the request, so an idle unit
 * never blocks a waiting turn for longer than one creation.
 *
 * One spare, never one for a later request (Stage B creates its own). LiteRT-LM 0.18 forgets the executor's last
 * session only once no session is left (`ThreadedExecutionManager::ReleaseSession`), so a conversation still alive
 * when another one closes makes the next request deep-copy the closed one's KV cache before its prefill. A Stage B
 * spare measured ~2.1 s slower to first token than a fresh conversation, nearly all it saved. For the same reason
 * [generate] closes a spare of the wrong kind before running, so its conversation is the only one alive.
 */
class LiteRtLmModel(
    private val context: Context,
    private val files: ModelFiles,
    private val backend: Backend,
    private val audioBackend: AudioBackend,
    private val maxTokens: Int = 2048,
    private val keepSpare: Boolean = true,
) : SpeechLanguageModel {

    private var engine: Engine? = null
    private var systemPrompt: String = ""
    private var lastTps: Double? = null
    private var winner: Rung? = null
    private val attempts = ArrayList<String>()

    /** The conversation [prepare] created for the next request, plain or made with the current [systemPrompt]. */
    private var spare: Spare? = null
    private val spareLock = Any() // close() may run on another thread than the pipeline's

    private class Spare(val conversation: Conversation, val withSystem: Boolean)

    @Volatile
    private var closed = false

    @Volatile
    private var active: Conversation? = null

    /** One attempt on the ladder: a backend and the bundle that goes with it. */
    private data class Rung(val backend: Backend, val file: File)

    override val info: ModelInfo
        get() = ModelInfo(
            name = winner?.file?.nameWithoutExtension ?: "gemma-4 (not loaded)",
            backend = winner?.backend?.name ?: "none",
            file = winner?.file?.absolutePath,
            lastDecodeTokensPerSec = lastTps,
        )

    /** Human-readable record of what was tried, for the bench and Logcat. */
    val ladderReport: String get() = attempts.joinToString("\n")

    override suspend fun warm(systemPrompt: String) {
        if (engine == null) engine = openEngine()
        if (systemPrompt != this.systemPrompt) dropSpare { it.withSystem } // made with the old prompt
        this.systemPrompt = systemPrompt
    }

    /**
     * Creates the spare for the first of [upcoming], replacing one of the other kind (the mode changed). Never more to
     * do. Only when [structured]: without the response format a conversation is ready in ~10 ms, and making a spare
     * would only hold up a turn that ends meanwhile.
     */
    override suspend fun prepare(upcoming: List<String>, structured: Boolean): Boolean {
        val e = engine ?: return false
        if (!keepSpare || !structured) return false
        val withSystem = usesSystemPrompt(upcoming.firstOrNull() ?: return false)
        if (synchronized(spareLock) { spare?.withSystem } == withSystem) return false
        dropSpare()
        val t0 = System.nanoTime()
        val conversation = newConversation(e, includeSystem = withSystem, responseFormat = true)
        synchronized(spareLock) {
            if (closed) {
                runCatching { conversation.close() }
                return false
            }
            spare = Spare(conversation, withSystem)
        }
        Log.i(TAG, "prepared a ${if (withSystem) "system" else "plain"} conversation in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return false
    }

    override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
        val e = engine ?: error("warm() first")
        val needsSystemPrompt = usesSystemPrompt(req.tag)
        val tCreate = System.nanoTime()
        val reused = takeSpare(needsSystemPrompt) // made with the response format, so it serves a request without a schema too
        val conversation = reused ?: newConversation(e, includeSystem = needsSystemPrompt, responseFormat = req.jsonSchema != null)
        val createMs = if (reused != null) "spare" else "${(System.nanoTime() - tCreate) / 1_000_000} ms"
        active = conversation
        val contents = ArrayList<Content>(2)
        req.audio?.let { turn -> contents += Content.AudioBytes(Wav.encode(turn.pcm, turn.sampleRate)) } // audio first
        contents += Content.Text(req.text)
        val responseFormat = req.jsonSchema?.let { ResponseFormat.json(it) }

        val t0 = System.nanoTime()
        var tokens = 0
        var firstAt = 0L
        val accumulated = StringBuilder()
        try {
            conversation.sendMessageAsync(Contents.of(contents), maxOutputToken = req.maxTokens, responseFormat = responseFormat)
                .collect { message ->
                    val piece = textOf(message.contents.contents)
                    if (piece.isEmpty()) return@collect
                    // Deltas per the docs; guard against a cumulative implementation anyway.
                    val delta = if (accumulated.isNotEmpty() && piece.startsWith(accumulated) && piece.length > accumulated.length) piece.substring(accumulated.length) else piece
                    if (delta.isEmpty()) return@collect
                    if (firstAt == 0L) firstAt = System.nanoTime()
                    tokens++
                    accumulated.append(delta)
                    emit(TokenChunk(delta))
                }
            val decodeSec = (System.nanoTime() - maxOf(firstAt, t0)) / 1e9
            val tps = if (decodeSec > 0 && tokens > 1) tokens / decodeSec else null
            lastTps = tps
            val prefillMs = if (firstAt > 0) (firstAt - t0) / 1_000_000 else null
            // Where a turn's time goes: creating the conversation, prefill up to the first token (all of it for a
            // fresh conversation: system + prompt + audio), then decode. "context" counts prompt + output tokens.
            Log.i(TAG, "${req.tag}: conversation $createMs, first token ${prefillMs ?: "–"} ms, $tokens tokens at ${tps?.let { "%.1f".format(it) } ?: "–"} tok/s, context ${runCatching { conversation.getTokenCount() }.getOrNull() ?: "?"} tokens")
            emit(TokenChunk("", done = true, stats = DecodeStats(outputTokens = tokens, prefillMs = prefillMs, decodeTokensPerSec = tps)))
        } finally {
            active = null
            runCatching { conversation.cancelProcess() } // no-op when finished; stops decode if the flow was cancelled
            runCatching { conversation.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { active?.cancelProcess() }
        dropSpare()
        runCatching { engine?.close() }
        engine = null
    }

    // ---- ladder --------------------------------------------------------------------------------

    private fun ladder(): List<Rung> {
        val generic = files.gemmaGeneric
        val soc = files.gemmaSoc
        fun acc(b: Backend): Rung? = soc?.let { Rung(b, it) }.also { if (it == null) attempts += "${b.name}: skipped, no SoC bundle on device (expected ${files.expectedSocBundleName() ?: "none for ${files.socModel}"})" }
        fun gen(b: Backend): Rung? = generic?.let { Rung(b, it) }.also { if (it == null) attempts += "${b.name}: skipped, no generic bundle on device (${ModelFiles.GEMMA_GENERIC})" }
        val rungs: List<Rung?> = when (backend) {
            Backend.AUTO -> when {
                files.isGoogleTensor -> listOf(acc(Backend.NPU), gen(Backend.GPU), gen(Backend.CPU))
                files.isQualcomm -> listOf(acc(Backend.NPU), gen(Backend.GPU), gen(Backend.CPU))
                else -> listOf(gen(Backend.GPU), gen(Backend.CPU))
            }
            Backend.GOOGLE_TENSOR, Backend.NPU -> listOf(acc(backend), gen(Backend.GPU), gen(Backend.CPU))
            Backend.GPU -> listOf(gen(Backend.GPU), gen(Backend.CPU))
            Backend.CPU -> listOf(gen(Backend.CPU))
        }
        return rungs.filterNotNull()
    }

    private fun openEngine(): Engine {
        attempts.clear()
        val rungs = ladder()
        check(rungs.isNotEmpty()) { "no Gemma bundle on device — ${files.pushCommand}" }
        var last: Throwable? = null
        for (rung in rungs) {
            val requestedAudio = audioBackendFor(rung.backend)
            val audioCandidates = if (requestedAudio is LlmBackend.CPU) listOf(requestedAudio) else listOf(requestedAudio, LlmBackend.CPU())
            for (audioB in audioCandidates) {
                val t0 = System.nanoTime()
                val cfg = EngineConfig(
                    modelPath = rung.file.absolutePath,
                    backend = rung.backend.toLlm(),
                    audioBackend = audioB,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
                val e = Engine(cfg)
                try {
                    e.initialize()
                    // LiteRT-LM builds the audio encoder with the engine's first conversation. Here a failure still falls
                    // through to the next audio backend, and the first turn doesn't pay for it (~150 ms).
                    e.createConversation(ConversationConfig()).close()
                    winner = rung
                    attempts += "${rung.backend.name} (audio=${audioB.javaClass.simpleName}) + ${rung.file.name}: OK in ${(System.nanoTime() - t0) / 1_000_000} ms"
                    Log.i(TAG, "engine ready: $ladderReport")
                    return e
                } catch (t: Throwable) {
                    last = t
                    attempts += "${rung.backend.name} (audio=${audioB.javaClass.simpleName}) + ${rung.file.name}: FAILED (${t.message?.lineSequence()?.firstOrNull()})"
                    Log.w(TAG, "backend ${rung.backend.name} (audio=${audioB.javaClass.simpleName}) failed: ${t.message}")
                    runCatching { e.close() }
                }
            }
        }
        throw IllegalStateException("no backend could load Gemma:\n$ladderReport", last)
    }

    private fun audioBackendFor(main: Backend): LlmBackend = when (audioBackend) {
        AudioBackend.CPU -> LlmBackend.CPU()
        AudioBackend.GPU -> LlmBackend.GPU()
        AudioBackend.SAME_AS_MAIN -> main.toLlm()
    }

    private fun newConversation(e: Engine, includeSystem: Boolean, responseFormat: Boolean): Conversation = e.createConversation(
        ConversationConfig(
            systemInstruction = if (includeSystem && systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null,
            prefillPrefaceOnInit = false,
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = responseFormat, // the ~2.5 s llguidance setup, see the class doc
        ),
    )

    /** Transcription and voice-card building stand alone; the reply-writing system prompt would only steer them off format. */
    private fun usesSystemPrompt(tag: String): Boolean =
        RequestTag.usesReplySystem(tag)

    /** The spare if its kind fits. One that doesn't is closed, so the request's conversation is the only one alive. */
    private fun takeSpare(withSystem: Boolean): Conversation? {
        val s = synchronized(spareLock) { spare.also { spare = null } } ?: return null
        if (s.withSystem == withSystem) return s.conversation
        runCatching { s.conversation.close() }
        return null
    }

    private fun dropSpare(onlyIf: (Spare) -> Boolean = { true }) {
        val s = synchronized(spareLock) { spare?.takeIf(onlyIf)?.also { spare = null } } ?: return
        runCatching { s.conversation.close() }
    }

    private fun textOf(contents: List<Content>): String {
        if (contents.isEmpty()) return ""
        val sb = StringBuilder()
        for (c in contents) if (c is Content.Text) sb.append(c.text)
        return sb.toString()
    }

    private fun Backend.toLlm(): LlmBackend = when (this) {
        Backend.CPU, Backend.AUTO -> LlmBackend.CPU()
        Backend.GPU -> LlmBackend.GPU()
        Backend.NPU, Backend.GOOGLE_TENSOR -> LlmBackend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    }

    private companion object {
        const val TAG = "LiteRtLmModel"
    }
}

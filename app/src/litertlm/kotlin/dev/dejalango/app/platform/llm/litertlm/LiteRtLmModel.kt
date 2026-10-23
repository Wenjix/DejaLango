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
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.TokenChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.ArrayDeque

/**
 * Gemma 4 E2B/E4B through LiteRT-LM's Kotlin API (`com.google.ai.edge.litertlm`). The only file in the
 * app that imports LiteRT-LM. Written against the API source in google-ai-edge/LiteRT-LM@main
 * (kotlin/java/com/google/ai/edge/litertlm/{Engine,Conversation,Config,Message,ResponseFormat}.kt):
 *
 * - `EngineConfig(modelPath, backend, audioBackend, maxNumTokens, cacheDir)` → `Engine(config).initialize()`
 * - `engine.createConversation(ConversationConfig(systemInstruction = Contents.of(text), prefillPrefaceOnInit = true,
 *    thinkingConfig = ThinkingConfig(enableThinking = false), enableResponseFormat = true))`
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
 * The Kotlin API has no session cloning, but `prefillPrefaceOnInit` prefills the system instruction when a
 * conversation is created. So the base-prompt cache is a small pool of pre-prefilled conversations: [warm]
 * fills it, each request consumes one, and [prepare] (called by the engine while idle) tops it up.
 */
class LiteRtLmModel(
    private val context: Context,
    private val files: ModelFiles,
    private val backend: Backend,
    private val audioBackend: AudioBackend,
    private val maxTokens: Int = 2048,
    private val poolSize: Int = 2,
) : SpeechLanguageModel {

    private var engine: Engine? = null
    private var systemPrompt: String = ""
    private var lastTps: Double? = null
    private var winner: Rung? = null
    private val pool = ArrayDeque<Conversation>()
    private val attempts = ArrayList<String>()

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
        if (this.systemPrompt != systemPrompt || pool.isEmpty()) {
            this.systemPrompt = systemPrompt
            drainPool()
            prepare()
        }
    }

    /** Top up the pool of pre-prefilled conversations. Cheap when full; one prefill per missing entry otherwise. */
    override suspend fun prepare() {
        val e = engine ?: return
        while (pool.size < poolSize) {
            val t0 = System.nanoTime()
            pool.addLast(newConversation(e))
            Log.d(TAG, "prefilled base conversation in ${(System.nanoTime() - t0) / 1_000_000} ms (pool=${pool.size})")
        }
    }

    override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
        val e = engine ?: error("warm() first")
        val conversation = pool.pollFirst() ?: newConversation(e)
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
            emit(TokenChunk("", done = true, stats = DecodeStats(outputTokens = tokens, prefillMs = if (firstAt > 0) (firstAt - t0) / 1_000_000 else null, decodeTokensPerSec = tps)))
        } finally {
            active = null
            runCatching { conversation.cancelProcess() } // no-op when finished; stops decode if the flow was cancelled
            runCatching { conversation.close() }
        }
    }

    override fun close() {
        runCatching { active?.cancelProcess() }
        drainPool()
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
                files.isGoogleTensor -> listOf(acc(Backend.GOOGLE_TENSOR), acc(Backend.NPU), gen(Backend.GPU), gen(Backend.CPU))
                files.isQualcomm -> listOf(acc(Backend.NPU), gen(Backend.GPU), gen(Backend.CPU))
                else -> listOf(gen(Backend.GPU), gen(Backend.CPU))
            }
            Backend.GOOGLE_TENSOR, Backend.NPU -> listOf(acc(backend), gen(Backend.CPU))
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
            val t0 = System.nanoTime()
            val cfg = EngineConfig(
                modelPath = rung.file.absolutePath,
                backend = rung.backend.toLlm(),
                audioBackend = audioBackendFor(rung.backend),
                maxNumTokens = maxTokens,
                cacheDir = context.cacheDir.absolutePath,
            )
            val e = Engine(cfg)
            try {
                e.initialize()
                winner = rung
                attempts += "${rung.backend.name} + ${rung.file.name}: OK in ${(System.nanoTime() - t0) / 1_000_000} ms"
                Log.i(TAG, "engine ready: $ladderReport")
                return e
            } catch (t: Throwable) {
                last = t
                attempts += "${rung.backend.name} + ${rung.file.name}: FAILED (${t.message?.lineSequence()?.firstOrNull()})"
                Log.w(TAG, "backend ${rung.backend.name} failed: ${t.message}")
                runCatching { e.close() }
            }
        }
        throw IllegalStateException("no backend could load Gemma:\n$ladderReport", last)
    }

    private fun audioBackendFor(main: Backend): LlmBackend = when (audioBackend) {
        AudioBackend.CPU -> LlmBackend.CPU()
        AudioBackend.GPU -> LlmBackend.GPU()
        AudioBackend.SAME_AS_MAIN -> main.toLlm()
    }

    private fun newConversation(e: Engine): Conversation = e.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            prefillPrefaceOnInit = true,
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = true,
        ),
    )

    private fun drainPool() {
        while (pool.isNotEmpty()) runCatching { pool.pollFirst()?.close() }
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
        Backend.NPU -> LlmBackend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Backend.GOOGLE_TENSOR -> LlmBackend.GOOGLE_TENSOR()
    }

    private companion object {
        const val TAG = "LiteRtLmModel"
    }
}

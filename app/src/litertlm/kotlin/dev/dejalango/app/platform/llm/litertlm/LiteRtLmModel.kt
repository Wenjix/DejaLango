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
import dev.dejalango.app.platform.audio.Wav
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
 * - `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, cacheDir)` → `Engine(config).initialize()`
 * - `engine.createConversation(ConversationConfig(systemInstruction = Contents.of(text), prefillPrefaceOnInit = true,
 *    thinkingConfig = ThinkingConfig(enableThinking = false), enableResponseFormat = true))`
 * - `conversation.sendMessageAsync(Contents.of(Content.AudioBytes(wav), Content.Text(prompt)), maxOutputToken = n,
 *    responseFormat = ResponseFormat.json(schema)): Flow<Message>` — streamed deltas; `message.contents.contents` are `Content`s
 * - `conversation.cancelProcess()`, `conversation.close()`, `engine.close()`
 * - GPU needs `<uses-native-library android:name="libOpenCL.so" .../>` in the manifest (done).
 *
 * The Kotlin API has no session cloning, but `prefillPrefaceOnInit` prefills the system instruction when a
 * conversation is created. So the base-prompt cache is a small pool of pre-prefilled conversations: [warm]
 * fills it, each request consumes one, and [prepare] (called by the engine while idle) tops it up. Audio content
 * goes before text; thinking is off; `jsonSchema` on a request turns on constrained decoding.
 *
 * Still to confirm on a device (Day-2 spike): WAV bytes are the expected `AudioBytes` payload (the C++ docs
 * use .wav paths), GPU vs CPU for `audioBackend`, and prefill cost of the pool refill.
 */
class LiteRtLmModel(
    private val context: Context,
    private val modelFile: File,
    private val backend: Backend,
    private val maxTokens: Int = 2048,
    private val audioBackend: Backend = Backend.CPU,
    private val poolSize: Int = 2,
) : SpeechLanguageModel {

    private var engine: Engine? = null
    private var systemPrompt: String = ""
    private var lastTps: Double? = null
    private var effectiveBackend: Backend = backend
    private val pool = ArrayDeque<Conversation>()

    @Volatile
    private var active: Conversation? = null

    override val info: ModelInfo
        get() = ModelInfo(name = modelFile.nameWithoutExtension, backend = effectiveBackend.name, file = modelFile.absolutePath, lastDecodeTokensPerSec = lastTps)

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

    private fun openEngine(): Engine {
        val base = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend.toLlm(),
            audioBackend = audioBackend.toLlm(),
            maxNumTokens = maxTokens,
            cacheDir = context.cacheDir.absolutePath,
        )
        return try {
            Engine(base).also { it.initialize() }
        } catch (t: Throwable) {
            if (backend == Backend.CPU) throw t
            Log.w(TAG, "${backend.name} backend failed (${t.message}); falling back to CPU")
            effectiveBackend = Backend.CPU
            Engine(base.copy(backend = LlmBackend.CPU(), audioBackend = LlmBackend.CPU())).also { it.initialize() }
        }
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
        Backend.CPU -> LlmBackend.CPU()
        Backend.GPU -> LlmBackend.GPU()
        Backend.NPU -> LlmBackend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    }

    private companion object {
        const val TAG = "LiteRtLmModel"
    }
}

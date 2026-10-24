package dev.dejalango.app.platform.llm.litertlm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LlmBackend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.dejalango.app.platform.ModelFiles
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.HashingEmbedder
import dev.dejalango.engine.voice.TextEmbedder
import dev.dejalango.engine.voice.Vectors
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * EmbeddingGemma 2 (text) through LiteRT-LM's `EmbeddingEngine` (kotlin/.../EmbeddingEngine.kt, EmbeddingConfig.kt):
 * `EmbeddingEngine(EmbeddingEngineConfig(modelPath, backend)).initialize()`, then
 * `computeEmbedding(listOf(InputData.Text(t)), EmbeddingOptions(normalize = true, outputSize = 256)).embedding`.
 * `outputSize` is the Matryoshka truncation; we normalize again defensively.
 *
 * **Backend ladder.** The bundle compiled for this phone's accelerator comes first ([ModelFiles.embedderSoc], the
 * `_Google_Tensor_G6` one for a Pixel 11's TPU), through `NPU` with the dispatch library in nativeLibraryDir, as for
 * Gemma. Then the generic bundle on CPU, not GPU, which Gemma uses. A rung wins once it has embedded a sentence, and
 * [description] names it. Each failure is logged. If no rung loads, or an embedding fails later, the hashing embedder
 * takes over, logged: its vectors carry no meaning.
 *
 * **Task prompts.** EmbeddingGemma was trained with a prompt per role: `task: search result | query: …` for what was
 * just said, `title: none | text: …` for the sentences the store keeps.
 */
class LiteRtLmEmbedder(private val context: Context, private val files: ModelFiles) : TextEmbedder {

    override val dims: Int = Vectors.DIMS
    private val fallback = HashingEmbedder(dims)

    /** One attempt on the ladder: a backend and the bundle that goes with it. */
    private class Rung(val name: String, val file: File, val backend: () -> LlmBackend)

    private var engine: EmbeddingEngine? = null

    @Volatile
    private var winner: Rung? = null

    @Volatile
    private var useFallback = false

    override val description: String
        get() = winner?.let { "${it.file.nameWithoutExtension} · ${it.name}" }
            ?: if (useFallback) "hashing fallback: EmbeddingGemma failed, see Logcat $TAG" else "EmbeddingGemma (not loaded)"

    override suspend fun warm() {
        open()
    }

    override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
        val e = open() ?: return fallback.embed(texts, role)
        return try {
            texts.map { embedOne(e, it, role) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "embedding failed on ${winner?.name}; using the hashing embedder from now on", t)
            close()
            useFallback = true
            fallback.embed(texts, role)
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
        winner = null
    }

    /** The engine of the first rung that works, opened on first use; null once none has. */
    private fun open(): EmbeddingEngine? {
        engine?.let { return it }
        if (useFallback) return null
        for (rung in ladder()) {
            val t0 = System.nanoTime()
            val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = rung.file.absolutePath, backend = rung.backend(), cacheDir = context.cacheDir.absolutePath))
            try {
                e.initialize()
                embedOne(e, "warm up", EmbedRole.QUERY) // a backend can initialize and still fail on its first run
                Log.i(TAG, "${rung.name} + ${rung.file.name}: ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
                engine = e
                winner = rung
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "${rung.name} + ${rung.file.name} failed: ${t.message}")
                runCatching { e.close() }
            }
        }
        useFallback = true
        Log.e(TAG, "no backend could load EmbeddingGemma (soc=${files.socModel}, soc bundle=${files.embedderSoc?.name}, generic=${files.embedderGeneric?.name}); using the hashing embedder")
        return null
    }

    private fun ladder(): List<Rung> = listOfNotNull(
        files.embedderSoc?.let { Rung("NPU", it) { LlmBackend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) } },
        files.embedderGeneric?.let { Rung("CPU", it) { LlmBackend.CPU() } },
    )

    private fun embedOne(e: EmbeddingEngine, text: String, role: EmbedRole): FloatArray {
        val r = e.computeEmbedding(listOf(InputData.Text(prompt(text, role))), EmbeddingOptions(normalize = true, outputSize = dims))
        return Vectors.normalize(Vectors.truncate(r.embedding, dims))
    }

    private companion object {
        const val TAG = "LiteRtLmEmbedder"

        fun prompt(text: String, role: EmbedRole): String = when (role) {
            EmbedRole.QUERY -> "task: search result | query: $text"
            EmbedRole.DOCUMENT -> "title: none | text: $text"
        }
    }
}

package dev.dejalango.app.platform.llm.litertlm

import android.content.Context
import com.google.ai.edge.litertlm.Backend as LlmBackend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.dejalango.engine.voice.TextEmbedder
import dev.dejalango.engine.voice.Vectors
import java.io.File

/**
 * EmbeddingGemma 2 (text) through LiteRT-LM's `EmbeddingEngine` (kotlin/.../EmbeddingEngine.kt, EmbeddingConfig.kt):
 * `EmbeddingEngine(EmbeddingEngineConfig(modelPath, backend)).initialize()`, then
 * `computeEmbedding(listOf(InputData.Text(t)), EmbeddingOptions(normalize = true, outputSize = 256)).embedding`.
 * `outputSize` is the Matryoshka truncation; we normalize again defensively.
 */
class LiteRtLmEmbedder(private val context: Context, private val modelFile: File) : TextEmbedder {

    override val dims: Int = Vectors.DIMS

    private val engine: EmbeddingEngine by lazy {
        EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelFile.absolutePath, backend = LlmBackend.CPU(), cacheDir = context.cacheDir.absolutePath))
            .also { it.initialize() }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { t ->
        val r = engine.computeEmbedding(listOf(InputData.Text(t)), EmbeddingOptions(normalize = true, outputSize = dims))
        Vectors.normalize(Vectors.truncate(r.embedding, dims))
    }

    fun close() {
        runCatching { engine.close() }
    }
}

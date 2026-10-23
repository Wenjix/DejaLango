package dev.dejalango.app.platform

import android.content.Context
import java.io.File

/**
 * Where model weights live on the device. They are pushed, not bundled: a 2.5 GB .litertlm cannot live
 * in an APK. `scripts/push_models.sh` does the push; the bench shows [pushCommand] when files are missing.
 */
class ModelFiles(context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val pkg: String = context.packageName

    val gemma: File get() = firstMatching(GEMMA_PATTERNS, GEMMA_CANONICAL)
    val embedder: File get() = firstMatching(EMBEDDER_PATTERNS, EMBEDDER_CANONICAL)

    val pushCommand: String
        get() = "adb push models/. /sdcard/Android/data/$pkg/files/models/"

    data class Status(val gemmaPresent: Boolean, val embedderPresent: Boolean, val gemmaFile: String, val embedderFile: String, val pushCommand: String) {
        val allPresent: Boolean get() = gemmaPresent && embedderPresent
    }

    fun status(): Status = Status(gemma.exists(), embedder.exists(), gemma.name, embedder.name, pushCommand)

    /** Any file matching one of the patterns, else the canonical name (which then doesn't exist). */
    private fun firstMatching(patterns: List<Regex>, canonical: String): File {
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        for (p in patterns) files.firstOrNull { p.matches(it.name) }?.let { return it }
        return File(dir, canonical)
    }

    companion object {
        const val GEMMA_CANONICAL = "gemma-4-E2B-it.litertlm"
        const val EMBEDDER_CANONICAL = "embeddinggemma-2-text.litertlm"

        // Names vary between the LiteRT community uploads; match loosely and keep the canonical one first.
        val GEMMA_PATTERNS = listOf(Regex("gemma-4-E2B-it\\.litertlm"), Regex("gemma-4-E2B.*\\.litertlm"), Regex("gemma-4-E4B.*\\.litertlm"), Regex("gemma.*\\.litertlm"))
        val EMBEDDER_PATTERNS = listOf(Regex("embeddinggemma-2-text\\.litertlm"), Regex("embeddinggemma.*\\.litertlm"), Regex(".*embed.*\\.litertlm"))
    }
}

package dev.dejalango.app.platform

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where model weights live on the device, and which bundle fits which backend. Weights are pushed, not
 * bundled: a 2.5–3.3 GB .litertlm cannot live in an APK. `scripts/push_models.sh` does the push; the bench
 * shows [pushCommand] when files are missing.
 *
 * Google publishes several Gemma 4 E2B bundles in `litert-community/gemma-4-E2B-it-litert-lm`:
 * the generic `gemma-4-E2B-it.litertlm` (CPU/GPU), `gemma-4-E2B-it-gpu.litertlm`, and SoC-compiled ones such as
 * `gemma-4-E2B-it_Google_Tensor_G6.litertlm` (Pixel 11), `_Google_Tensor_G5` (Pixel 10) and
 * `_qualcomm_sm8750` (Galaxy S25). The SoC bundle is only valid on that chip's accelerator; everything else
 * uses the generic one. We match by `Build.SOC_MODEL` so the right file is picked on whatever phone is plugged in.
 */
class ModelFiles(context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val pkg: String = context.packageName

    /** e.g. "Tensor G6", "Tensor_G6", "SM8750". Normalized to lowercase with underscores for matching. */
    val socModel: String = Build.SOC_MODEL.orEmpty()
    val socManufacturer: String = Build.SOC_MANUFACTURER.orEmpty()
    val socKey: String = normalize(socModel)

    val isGoogleTensor: Boolean get() = socManufacturer.equals("Google", true) || socKey.startsWith("tensor")
    val isQualcomm: Boolean get() = socManufacturer.equals("Qualcomm", true) || socKey.startsWith("sm8")

    /** Generic bundle for CPU/GPU. Prefers the plain file; accepts the -gpu one or anything gemma-ish. */
    val gemmaGeneric: File?
        get() = candidates().firstOrNull { it.name.equals(GEMMA_GENERIC, true) }
            ?: candidates().firstOrNull { it.name.equals("gemma-4-E2B-it-gpu.litertlm", true) }
            ?: candidates().firstOrNull { n -> n.name.lowercase().let { it.startsWith("gemma") && !isSocBundle(it) } }

    /** SoC-compiled bundle for this phone's accelerator, if one has been pushed. */
    val gemmaSoc: File?
        get() = if (socKey.isEmpty()) null else candidates().firstOrNull { f ->
            val n = normalize(f.name)
            n.startsWith("gemma") && isSocBundle(n) && n.contains(socKey)
        }

    val embedder: File?
        get() = candidates().firstOrNull { it.name.equals(EMBEDDER_CANONICAL, true) }
            ?: candidates().firstOrNull { it.name.lowercase().let { n -> n.contains("embeddinggemma") && n.contains("text") && !isSocBundle(n) } }
            ?: candidates().firstOrNull { it.name.lowercase().contains("embed") }

    val pushCommand: String
        get() = "adb push models/. /sdcard/Android/data/$pkg/files/models/"

    data class Status(
        val socModel: String,
        val gemmaGeneric: String?,
        val gemmaSoc: String?,
        val embedder: String?,
        val pushCommand: String,
    ) {
        val gemmaPresent: Boolean get() = gemmaGeneric != null || gemmaSoc != null
        val allPresent: Boolean get() = gemmaPresent && embedder != null
    }

    fun status(): Status = Status(socModel, gemmaGeneric?.name, gemmaSoc?.name, embedder?.name, pushCommand)

    /** The file name scripts/fetch_models.sh would produce for this phone's SoC, for the bench's hint. */
    fun expectedSocBundleName(): String? = when {
        isGoogleTensor -> "gemma-4-E2B-it_Google_${socModel.replace(' ', '_')}.litertlm"
        isQualcomm -> "gemma-4-E2B-it_qualcomm_${socModel.lowercase()}.litertlm"
        else -> null
    }

    private fun candidates(): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm", true) }?.sortedBy { it.name } ?: emptyList()

    companion object {
        const val GEMMA_GENERIC = "gemma-4-E2B-it.litertlm"
        const val EMBEDDER_CANONICAL = "embeddinggemma-2-text.litertlm"

        fun normalize(s: String): String = s.lowercase().replace(' ', '_').replace('-', '_')

        /** SoC bundles are named `<model>_<vendor>_<soc>.litertlm`. */
        fun isSocBundle(lowerName: String): Boolean =
            lowerName.contains("_google_") || lowerName.contains("_qualcomm_") || lowerName.contains("_mediatek_") || lowerName.contains("_intel_") || lowerName.contains("tensor_g")
    }
}

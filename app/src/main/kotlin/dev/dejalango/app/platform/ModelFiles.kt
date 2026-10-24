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
    /**
     * Internal storage, where `scripts/push_models.sh` puts the weights. Not the external files dir: that is FUSE, and
     * Google Tensor's TPU runtime reads Gemma's `_Google_Tensor_Gx` bundle with preadv2, which FUSE refuses
     * (EOPNOTSUPP), so Gemma fell back to the GPU.
     */
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    /** Where weights were pushed before; still read, after [dir]. */
    private val externalDir: File? = context.getExternalFilesDir(null)?.let { File(it, "models") }
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

    /** Generic EmbeddingGemma 2 text bundle, run on CPU: the embedder's fallback. */
    val embedderGeneric: File?
        get() = candidate(::genericEmbedderName)

    /** EmbeddingGemma 2 text bundle compiled for this phone's accelerator (the Pixel 11's TPU): the embedder's default. */
    val embedderSoc: File?
        get() = candidate { socEmbedderName(it, socKey) }

    val pushCommand: String
        get() = "scripts/push_models.sh $pkg"

    data class Status(
        val socModel: String,
        val gemmaGeneric: String?,
        val gemmaSoc: String?,
        val embedderGeneric: String?,
        val embedderSoc: String?,
        val pushCommand: String,
    ) {
        val gemmaPresent: Boolean get() = gemmaGeneric != null || gemmaSoc != null
        val embedderPresent: Boolean get() = embedderGeneric != null || embedderSoc != null
        val allPresent: Boolean get() = gemmaPresent && embedderPresent
    }

    fun status(): Status = Status(socModel, gemmaGeneric?.name, gemmaSoc?.name, embedderGeneric?.name, embedderSoc?.name, pushCommand)

    /** The file name scripts/fetch_models.sh would produce for this phone's SoC, for the bench's hint. */
    fun expectedSocBundleName(): String? = when {
        isGoogleTensor -> "gemma-4-E2B-it_Google_${socModel.replace(' ', '_')}.litertlm"
        isQualcomm -> "gemma-4-E2B-it_qualcomm_${socModel.lowercase()}.litertlm"
        else -> null
    }

    private fun candidates(): List<File> = mergeCandidates(bundlesIn(dir), externalDir?.let(::bundlesIn).orEmpty())

    /** The candidate [pick] names, in whichever directory it lives. */
    private fun candidate(pick: (List<String>) -> String?): File? =
        candidates().let { c -> pick(c.map { it.name })?.let { name -> c.first { it.name == name } } }

    private fun bundlesIn(d: File): List<File> = d.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm", true) } ?: emptyList()

    companion object {
        const val GEMMA_GENERIC = "gemma-4-E2B-it.litertlm"

        fun normalize(s: String): String = s.lowercase().replace(' ', '_').replace('-', '_')

        /** SoC bundles are named `<model>_<vendor>_<soc>.litertlm`. */
        fun isSocBundle(lowerName: String): Boolean =
            lowerName.contains("_google_") || lowerName.contains("_qualcomm_") || lowerName.contains("_mediatek_") || lowerName.contains("_intel_") || lowerName.contains("tensor_g")

        /** One file per name, sorted by name; a copy in [internal] storage beats one in [external]. */
        fun mergeCandidates(internal: List<File>, external: List<File>): List<File> =
            (internal + external).distinctBy { it.name }.sortedBy { it.name }

        /** An EmbeddingGemma 2 text bundle. Not the multimodal 740M: its text vectors needn't match the 270M's in the store. */
        private fun isTextEmbedder(lowerName: String): Boolean = lowerName.startsWith("embeddinggemma") && lowerName.contains("text")

        /** The generic (CPU/GPU) text embedder among [names]. Never a vendor build: each runs only on its own accelerator. */
        fun genericEmbedderName(names: List<String>): String? =
            names.firstOrNull { it.lowercase().let { n -> isTextEmbedder(n) && !isSocBundle(n) } }

        /** The text embedder compiled for the accelerator of the SoC [socKey] (as [normalize] writes it), or null. */
        fun socEmbedderName(names: List<String>, socKey: String): String? =
            if (socKey.isEmpty()) null else names.firstOrNull { normalize(it).let { n -> isTextEmbedder(n) && isSocBundle(n) && n.contains(socKey) } }
    }
}

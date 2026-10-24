package dev.dejalango.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Which EmbeddingGemma bundle the embedder loads, from the file names Hugging Face publishes. */
class ModelFilesTest {

    // Every build in litert-community/embeddinggemma-2-text-270m-litert-lm (2026-10-23).
    private val text270m = listOf(
        "embeddinggemma-2-text-270m.litertlm",
        "embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm",
        "embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm",
        "embeddinggemma-2-text-270m_Intel_PTL.litertlm",
        "embeddinggemma-2-text-270m_MediaTek_MT6991.litertlm",
        "embeddinggemma-2-text-270m_MediaTek_MT6993.litertlm",
        "embeddinggemma-2-text-270m_Qualcomm_QCS8275.litertlm",
        "embeddinggemma-2-text-270m_Qualcomm_SM8550.litertlm",
        "embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm",
        "embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm",
        "embeddinggemma-2-text-270m_Qualcomm_SM8850.litertlm",
    )
    private val multimodal740m = listOf("embeddinggemma-2-740m.litertlm", "embeddinggemma-2-740m_Google_Tensor_G6.litertlm")
    private val gemma = listOf("gemma-4-E2B-it.litertlm", "gemma-4-E2B-it_Google_Tensor_G6.litertlm")

    @Test
    fun `a Pixel 11 gets the Tensor G6 text build, with the generic text build as its fallback`() {
        val names = (gemma + multimodal740m + text270m).sorted()
        assertEquals("embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm", ModelFiles.socEmbedderName(names, "tensor_g6"))
        assertEquals("embeddinggemma-2-text-270m.litertlm", ModelFiles.genericEmbedderName(names))
    }

    @Test
    fun `a vendor build never stands in for the generic one`() {
        // Each runs only on its own accelerator: the Intel NPU build once posed as the generic file and never loaded.
        assertNull(ModelFiles.genericEmbedderName((text270m - "embeddinggemma-2-text-270m.litertlm").sorted()))
    }

    @Test
    fun `another phone's accelerator build is not this phone's`() {
        val names = listOf("embeddinggemma-2-text-270m.litertlm", "embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm")
        assertNull(ModelFiles.socEmbedderName(names, "tensor_g6"))
        assertEquals("embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm", ModelFiles.socEmbedderName(names, "tensor_g5"))
    }

    @Test
    fun `the multimodal 740M model is never picked for text retrieval`() {
        // Its text vectors needn't match the 270M's, and the exemplar store keeps whatever it was embedded with.
        val names = (gemma + multimodal740m).sorted()
        assertNull(ModelFiles.genericEmbedderName(names))
        assertNull(ModelFiles.socEmbedderName(names, "tensor_g6"))
    }

    @Test
    fun `a phone that reports no SoC gets no accelerator build`() {
        assertNull(ModelFiles.socEmbedderName(text270m, ""))
    }

    @Test
    fun `a bundle in internal storage beats its copy in the FUSE external dir, and nothing else is lost`() {
        // Google Tensor's TPU runtime can't read Gemma's bundle on FUSE (preadv2: EOPNOTSUPP), so Gemma fell back to GPU.
        val internal = "/data/user/0/dev.dejalango/files/models"
        val external = "/storage/emulated/0/Android/data/dev.dejalango/files/models"
        val merged = ModelFiles.mergeCandidates(
            internal = listOf(File("$internal/gemma-4-E2B-it_Google_Tensor_G6.litertlm")),
            external = listOf(File("$external/gemma-4-E2B-it_Google_Tensor_G6.litertlm"), File("$external/gemma-4-E2B-it.litertlm")),
        )
        assertEquals(
            listOf("$external/gemma-4-E2B-it.litertlm", "$internal/gemma-4-E2B-it_Google_Tensor_G6.litertlm"),
            merged.map { it.path },
        )
    }
}

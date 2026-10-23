package dev.dejalango.engine.config

/** What the wearer is doing. Modes differ only in trigger policy, prompt, and HUD layout. */
enum class Mode {
    /** They speak; I get two replies in their language, in my voice. */
    REPLY_OPTIONS,

    /** I speak L1; I get the same thing in L2, in my voice. */
    SAY_IT_MY_WAY,

    /** I pause mid-sentence; I get the word I'm reaching for, from my own vocabulary. */
    WORD_FINDING,
}

enum class PipelineShape {
    /** Stage A transcribes+translates (transcript visible early); stage B retrieves exemplars and writes replies. */
    TWO_STAGE,

    /** One call does everything; exemplars come from the previous turn's text (one turn stale, zero extra prefill). */
    SINGLE,
}

/**
 * Where the decoder runs. [AUTO] lets the adapter pick a ladder for the device
 * (Pixel: GOOGLE_TENSOR → NPU → GPU → CPU; Qualcomm: NPU → GPU → CPU; else GPU → CPU);
 * an explicit value is tried first with CPU as the only fallback. The winner is reported in ModelInfo.
 */
enum class Backend { AUTO, CPU, GPU, NPU, GOOGLE_TENSOR }

/** Where the audio encoder runs. TPU bundles may or may not carry it; CPU is the safe default. */
enum class AudioBackend { CPU, GPU, SAME_AS_MAIN }

/** A language the app knows how to prompt for. Add entries, not special cases. */
data class Language(
    val code: String,            // BCP-47 primary tag: "en", "zh"
    val name: String,            // "English", "Mandarin Chinese"
    val romanization: String?,   // null for Latin-script languages
    val cjk: Boolean,            // reply length is counted in characters, not words
) {
    companion object {
        val EN = Language("en", "English", null, cjk = false)
        val ZH = Language("zh", "Mandarin Chinese", "Hanyu Pinyin with tone marks", cjk = true)
        val ES = Language("es", "Spanish", null, cjk = false)
        val JA = Language("ja", "Japanese", "Hepburn romaji", cjk = true)
        val KO = Language("ko", "Korean", "Revised Romanization", cjk = false)
        val ALL = listOf(EN, ZH, ES, JA, KO)
        fun byCode(code: String): Language =
            ALL.firstOrNull { it.code.equals(code.substringBefore('-'), ignoreCase = true) }
                ?: Language(code, code, null, cjk = false)
    }
}

/**
 * Every knob you turn at the workbench. Persisted by the app (DataStore); edited from the bench's
 * debug sheet; observed by the pipeline as a StateFlow so changes apply to the next turn.
 */
data class PipelineConfig(
    val mode: Mode = Mode.REPLY_OPTIONS,
    val l1: Language = Language.EN,
    val l2: Language = Language.ZH,
    val shape: PipelineShape = PipelineShape.TWO_STAGE,
    val backend: Backend = Backend.AUTO,
    val audioBackend: AudioBackend = AudioBackend.CPU,
    val replies: Int = 2,
    val maxReplyWords: Int = 12,
    val maxReplyChars: Int = 20,       // CJK cap
    val exemplarsK: Int = 6,
    val contextTurns: Int = 6,
    val generationTimeoutMs: Long = 8_000,
    val thinking: Boolean = false,
    val speakReplies: Boolean = false, // TTS the chosen reply into an earbud
    val structuredOutput: Boolean = false, // constrained JSON decoding where the runtime supports it
) {
    val maxUnitsText: String
        get() = if (l2.cjk) "$maxReplyChars ${l2.name} characters" else "$maxReplyWords words"
}

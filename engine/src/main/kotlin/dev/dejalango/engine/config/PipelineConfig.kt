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
 * (Google Tensor or Qualcomm: NPU → GPU → CPU; else GPU → CPU); an explicit NPU or GOOGLE_TENSOR
 * falls back to GPU, then CPU, and GPU to CPU. The winner is reported in ModelInfo.
 */
enum class Backend { AUTO, CPU, GPU, NPU, GOOGLE_TENSOR }

/** Where the audio encoder runs. TPU bundles may or may not carry it; CPU is the safe default. */
enum class AudioBackend { CPU, GPU, SAME_AS_MAIN }

/** A language the app knows how to prompt for. Add entries, not special cases. */
data class Language(
    val code: String,            // Exemplar/card key; shared by regions, distinct for Traditional.
    val name: String,            // "English", "Mandarin Chinese"
    val romanization: String?,   // null for Latin-script languages
    val cjk: Boolean,            // reply length is counted in characters, not words
    val tag: String = code,
    val label: String = name,
) {
    val base: String get() = code.substringBefore('-')
    val traditional: Boolean get() = tag.equals("zh-TW", true) || code.equals("zh-Hant", true)
    val promptName: String get() = when {
        base == "zh" -> if (traditional) "Mandarin Chinese (Taiwan, Traditional characters)" else "Mandarin Chinese (Mainland, Simplified characters)"
        else -> label
    }
    val scriptLabel: String? get() = if (base == "zh") if (traditional) "Traditional" else "Simplified" else null

    companion object {
        val EN = Language("en", "English", null, cjk = false, tag = "en-US", label = "English (US)")
        val EN_GB = EN.copy(tag = "en-GB", label = "English (UK)")
        val EN_AU = EN.copy(tag = "en-AU", label = "English (Australia)")
        val ZH = Language("zh", "Mandarin Chinese", "Hanyu Pinyin with tone marks", cjk = true, tag = "zh-CN", label = "Mandarin (Mainland)")
        val ZH_TW = Language("zh-Hant", "Mandarin Chinese (Traditional)", "Hanyu Pinyin with tone marks", cjk = true, tag = "zh-TW", label = "Mandarin (Taiwan)")
        val ES = Language("es", "Spanish", null, cjk = false, tag = "es-CO", label = "Spanish (Colombia)")
        val ES_MX = ES.copy(tag = "es-MX", label = "Spanish (Mexico)")
        val ES_ES = ES.copy(tag = "es-ES", label = "Spanish (Spain)")
        val JA = Language("ja", "Japanese", "Hepburn romaji", cjk = true)
        val KO = Language("ko", "Korean", "Revised Romanization", cjk = false)
        val VARIETIES = listOf(EN, EN_GB, EN_AU, ES, ES_MX, ES_ES, ZH, ZH_TW)
        val ALL = VARIETIES
        fun byCode(code: String): Language {
            val normalized = code.replace('_', '-')
            return VARIETIES.firstOrNull { it.tag.equals(normalized, true) || it.code.equals(normalized, true) }
                ?: when {
                    normalized.startsWith("zh-Hant", true) -> ZH_TW
                    normalized.startsWith("zh", true) -> ZH
                    normalized.startsWith("en", true) -> EN
                    normalized.startsWith("es", true) -> ES
                    normalized == "ja" -> JA
                    normalized == "ko" -> KO
                    else -> Language(normalized, normalized, null, cjk = false)
                }
        }
    }
}

data class LanguagePair(val me: Language, val them: Language) {
    init { require(isValid(me, them)) { "Choose two different base languages" } }
    fun swapped(): LanguagePair = LanguagePair(them, me)
    companion object {
        fun isValid(me: Language, them: Language): Boolean = me.base != them.base
        fun restored(me: String?, them: String?): LanguagePair {
            val a = me?.let(Language::byCode)?.takeIf { it in Language.VARIETIES } ?: Language.EN
            val b = them?.let(Language::byCode)?.takeIf { it in Language.VARIETIES && it.base != a.base }
                ?: if (a.base == "zh") Language.EN else Language.ZH
            return LanguagePair(a, b)
        }
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
    val generationTimeoutMs: Long = 20_000,
    val thinking: Boolean = false,
    val speakReplies: Boolean = false, // TTS the chosen reply into an earbud
    val structuredOutput: Boolean = false, // constrained JSON decoding where the runtime supports it
    val catchForgottenTaps: Boolean = true,
    /** The active playbook scene's id, or [ALL_SCENES] for every scene. Blank turns the playbook off, so Stage B writes every reply. */
    val playbookScene: String = "",
    /** The least score a playbook hit needs: the cosine between their line and the nearest seeded one. */
    val playbookThreshold: Float = 0.85f,
    /** How far a playbook hit must beat every other moment's local lines. */
    val playbookMargin: Float = 0.03f,
) {
    val pair: LanguagePair get() = LanguagePair(l1, l2)
    val maxUnitsText: String
        get() = if (l2.cjk) "$maxReplyChars ${l2.name} characters" else "$maxReplyWords words"

    companion object {
        /** The [playbookScene] that matches their line against every scene of the playbook. */
        const val ALL_SCENES = "*"
    }
}

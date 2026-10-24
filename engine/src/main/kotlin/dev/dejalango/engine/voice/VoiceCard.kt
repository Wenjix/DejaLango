package dev.dejalango.engine.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Confidence {
    /** Built from an hour or more of transcript. */
    FULL,

    /** Built from ~60 s of a judge talking. In-memory only. */
    QUICK,

    /** No card yet; the system prompt leaves the voice card out. */
    NONE,
}

/**
 * How ME talks. Lives in the cached base prompt; rebuilt rarely. Scales are 1..5.
 * Hand-rolled JSON codec (JsonElement tree API) so :engine needs no serialization compiler plugin.
 */
data class VoiceCard(
    val l1: String,
    val l2: String,
    val register: Int = 3,
    val directness: Int = 3,
    val warmth: Int = 3,
    val humor: Int = 3,
    val avgSentenceWords: Int = 10,
    val fillers: List<String> = emptyList(),
    val openers: List<String> = emptyList(),
    val closers: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
    val avoids: List<String> = emptyList(),
    val notes: String = "",
    val confidence: Confidence = Confidence.NONE,
) {
    /**
     * Compact rendering for the system prompt, every part capped so a long card can't bloat prefill. Blank without a
     * card, so the prompt's voice-card section drops out. Scale values of 3 say nothing and are left out; so are
     * avoids, which are L1 phrases an L2 reply can't contain anyway.
     */
    fun promptText(): String {
        if (confidence == Confidence.NONE) return ""
        val tone = listOfNotNull(
            tone(register, "very casual", "casual", "polite", "formal"),
            tone(directness, "very indirect", "indirect", "direct", "blunt"),
            tone(warmth, "cold", "reserved", "warm", "very warm"),
            tone(humor, "no humor", "little humor", "playful", "jokey"),
            when {
                avgSentenceWords <= 6 -> "very short sentences"
                avgSentenceWords <= 9 -> "short sentences"
                else -> null // never "long": the prompt caps every line's length anyway
            },
        ).joinToString(", ").ifEmpty { "neutral tone" }
        val lines = arrayListOf((if (confidence == Confidence.QUICK) "(low confidence) " else "") + tone)
        val seen = HashSet<String>() // shared, so a phrase listed as both filler and signature appears once
        // Whole sentences aren't habits: a model that copies them here would teach the reply to copy them too.
        unique(fillers.filter { it.isPhrase(maxWords = 2) }, seen, MAX_FILLERS).takeIf { it.isNotEmpty() }?.let { lines += "fillers: " + it.joinToString(", ") }
        unique((signaturePhrases + openers + closers).filter { it.isPhrase(maxWords = 5) }, seen, MAX_MOVES)
            .takeIf { it.isNotEmpty() }?.let { lines += "signature moves: " + it.joinToString(" | ") }
        capNotes(notes).takeIf { it.isNotEmpty() }?.let { lines += it }
        return lines.joinToString("\n")
    }

    /** The card as the bench and HUD show it: [promptText] plus what I never say. */
    fun describe(): String {
        if (confidence == Confidence.NONE) return "(no voice card yet)"
        val never = unique(avoids, HashSet(), Int.MAX_VALUE)
        return promptText() + if (never.isEmpty()) "" else "\nnever says: " + never.joinToString(" | ")
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("l1", l1); put("l2", l2)
        put("register", register); put("directness", directness); put("warmth", warmth); put("humor", humor)
        put("avgSentenceWords", avgSentenceWords)
        put("fillers", JsonArray(fillers.map { JsonPrimitive(it) }))
        put("openers", JsonArray(openers.map { JsonPrimitive(it) }))
        put("closers", JsonArray(closers.map { JsonPrimitive(it) }))
        put("signaturePhrases", JsonArray(signaturePhrases.map { JsonPrimitive(it) }))
        put("avoids", JsonArray(avoids.map { JsonPrimitive(it) }))
        put("notes", notes)
        put("confidence", confidence.name)
    }

    companion object {
        private const val MAX_FILLERS = 3
        private const val MAX_MOVES = 4
        private const val MAX_NOTES = 120
        private const val TRAILING = " \t\n,;:，；：、"

        /** The word for a 1..5 scale value; the middle value says nothing. */
        private fun tone(v: Int, w1: String, w2: String, w4: String, w5: String): String? = when (v.coerceIn(1, 5)) {
            1 -> w1
            2 -> w2
            4 -> w4
            5 -> w5
            else -> null
        }

        /** Han and kana are written without spaces; Hangul is spaced, so whitespace already counts Korean words. */
        private val UNSPACED_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

        /** A habit, not a sentence: at most [maxWords] words, an unspaced run counting as one word per ~1.5 characters. */
        private fun String.isPhrase(maxWords: Int): Boolean {
            val s = trim()
            val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.sumOf { token ->
                val unspaced = token.count { Character.UnicodeScript.of(it.code) in UNSPACED_SCRIPTS }
                if (unspaced == 0) 1 else (unspaced * 2 + 2) / 3 // ceil(unspaced / 1.5)
            }
            return words in 1..maxWords && s.length <= maxWords * 8
        }

        /** Trimmed, without trailing separators (ASCII or full-width); keeps . ? ! */
        private fun clean(s: String): String = s.trim().trimEnd { it in TRAILING }.trim()

        /** Dedupe key: lowercase letters and digits in any script, everything else collapsed to single spaces. */
        private fun key(s: String): String =
            s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        /** The first [limit] cleaned items whose key isn't in [seen]; lazy, so only kept items claim a key. */
        private fun unique(items: List<String>, seen: MutableSet<String>, limit: Int): List<String> =
            items.asSequence().map(::clean).filter { it.isNotEmpty() }.filter { key(it).let { k -> k.isNotEmpty() && seen.add(k) } }.take(limit).toList()

        private fun capNotes(notes: String): String {
            val n = notes.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (n.length <= MAX_NOTES) return n
            val cut = n.take(MAX_NOTES).let { c -> c.lastIndexOf(' ').takeIf { it > 0 }?.let { c.substring(0, it) } ?: c }
            return clean(cut) + "…"
        }

        fun none(l1: String, l2: String): VoiceCard = VoiceCard(l1 = l1, l2 = l2, confidence = Confidence.NONE)

        /** Tolerant: every field optional, scales clamped, lists of non-strings dropped. */
        fun fromJson(o: JsonObject, l1Default: String = "en", l2Default: String = "zh", confidence: Confidence? = null): VoiceCard {
            fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun int(k: String, d: Int): Int = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: d
            fun list(k: String): List<String> = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val conf = confidence ?: str("confidence")?.let { c -> Confidence.values().firstOrNull { it.name.equals(c, true) } } ?: Confidence.FULL
            return VoiceCard(
                l1 = str("l1") ?: l1Default,
                l2 = str("l2") ?: l2Default,
                register = int("register", 3).coerceIn(1, 5),
                directness = int("directness", 3).coerceIn(1, 5),
                warmth = int("warmth", 3).coerceIn(1, 5),
                humor = int("humor", 3).coerceIn(1, 5),
                avgSentenceWords = int("avgSentenceWords", 10).coerceIn(2, 40),
                fillers = list("fillers"),
                openers = list("openers"),
                closers = list("closers"),
                signaturePhrases = list("signaturePhrases"),
                avoids = list("avoids"),
                notes = str("notes") ?: "",
                confidence = conf,
            )
        }
    }
}

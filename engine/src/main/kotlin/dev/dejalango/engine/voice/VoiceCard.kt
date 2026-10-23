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

    /** No card yet; prompts fall back to a neutral register. */
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
    /** Compact natural-language rendering for the system prompt. */
    fun describe(): String {
        if (confidence == Confidence.NONE) return "(no voice card yet — use a neutral, friendly spoken register)"
        val sb = StringBuilder()
        sb.append("register ${scale(register, "very casual", "very formal")}; ")
        sb.append("directness ${scale(directness, "hedged", "blunt")}; ")
        sb.append("warmth ${scale(warmth, "reserved", "very warm")}; ")
        sb.append("humor ${scale(humor, "none", "constant")}; ")
        sb.append("sentences about $avgSentenceWords words.")
        if (fillers.isNotEmpty()) sb.append("\nfillers: ${fillers.joinToString(", ")}")
        if (openers.isNotEmpty()) sb.append("\nopeners: ${openers.joinToString(" | ")}")
        if (closers.isNotEmpty()) sb.append("\nclosers: ${closers.joinToString(" | ")}")
        if (signaturePhrases.isNotEmpty()) sb.append("\nsignature phrases: ${signaturePhrases.joinToString(" | ")}")
        if (avoids.isNotEmpty()) sb.append("\nnever says: ${avoids.joinToString(" | ")}")
        if (notes.isNotBlank()) sb.append("\nnotes: $notes")
        if (confidence == Confidence.QUICK) sb.append("\n(low confidence: built from one minute of speech)")
        return sb.toString()
    }

    private fun scale(v: Int, lo: String, hi: String): String = when (v.coerceIn(1, 5)) {
        1 -> lo
        2 -> "fairly $lo"
        3 -> "middle of the road"
        4 -> "fairly $hi"
        else -> hi
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

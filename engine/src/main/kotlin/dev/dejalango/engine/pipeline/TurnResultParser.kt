package dev.dejalango.engine.pipeline

import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.Reply
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.VoiceCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class StageAResult(val speaker: Speaker, val lang: String, val heard: String, val heardL1: String, val repaired: Boolean = false)

/** A JSON object pulled out of model text. [repaired] means truncated output was closed up to parse. */
data class Extracted(val obj: JsonObject, val repaired: Boolean)

data class WordHint(val word: String, val alternatives: List<String>, val confidence: Float)

data class TranscriptResult(val lang: String, val text: String)

/**
 * Lenient parsing of model output. The model is told "JSON only"; the parser assumes it won't always
 * listen: fences, leading prose, trailing commentary, missing fields, wrong types. On total failure
 * callers fall back to showing raw text — never a blank HUD.
 */
class TurnResultParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** First balanced `{...}` in [text], parsed; null if there is none or it doesn't parse. */
    fun extractJsonObject(text: String): JsonObject? = extract(text)?.obj

    fun extract(text: String): Extracted? {
        val stripped = stripFences(text)
        val start = stripped.indexOf('{')
        if (start < 0) return null
        val end = matchingBrace(stripped, start)
        val candidates = ArrayList<Pair<String, Boolean>>(4)
        if (end > start) candidates += stripped.substring(start, end + 1) to false
        val lastClose = stripped.lastIndexOf('}')
        if (lastClose > start) candidates += stripped.substring(start, lastClose + 1) to false
        // Truncated generations (maxTokens hit mid-string) are common; try closing them.
        val tail = stripped.substring(start).trimEnd().trimEnd(',')
        for (suffix in TRUNCATION_REPAIRS) candidates += (tail + suffix) to true
        for ((c, repaired) in candidates) {
            try {
                val el = json.parseToJsonElement(c)
                if (el is JsonObject) return Extracted(el, repaired)
            } catch (_: Exception) {
                // try next candidate
            }
        }
        return null
    }

    fun parseStageA(text: String): StageAResult? = extract(text)?.let { parseStageA(it) }

    fun parseStageA(e: Extracted): StageAResult? {
        val o = e.obj
        val heard = o.str("heard") ?: return null
        val speaker = when (o.str("speaker")?.lowercase()?.trim()) {
            "me", "wearer", "user" -> Speaker.ME
            "them", "other" -> Speaker.THEM
            else -> Speaker.THEM
        }
        return StageAResult(
            speaker = speaker,
            lang = o.str("lang")?.trim().orEmpty(),
            heard = heard.trim(),
            heardL1 = (o.str("heard_l1") ?: o.str("heardL1") ?: o.str("translation") ?: heard).trim(),
            repaired = e.repaired,
        )
    }

    /** Replies from a stage-B or single-call answer. A top-level reply object is accepted as a list of one. */
    fun parseReplies(text: String): List<Reply> {
        val o = extractJsonObject(text) ?: return emptyList()
        return parseReplies(o)
    }

    fun parseReplies(o: JsonObject): List<Reply> {
        val arr = o["replies"] as? JsonArray
        val items: List<JsonObject> = when {
            arr != null -> arr.filterIsInstance<JsonObject>()
            o.containsKey("yours") || o.containsKey("plain") -> listOf(o)
            else -> emptyList()
        }
        return items.mapNotNull { r ->
            val plain = r.str("plain")?.trim()
            val yours = r.str("yours")?.trim()
            val any = yours?.takeIf { it.isNotEmpty() } ?: plain?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Reply(
                plain = plain?.takeIf { it.isNotEmpty() } ?: any,
                yours = yours?.takeIf { it.isNotEmpty() } ?: any,
                roman = r.str("roman")?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
                back = r.str("back")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    fun parseWordHint(text: String): WordHint? {
        val o = extractJsonObject(text) ?: return null
        val word = o.str("word")?.trim() ?: return null
        val alts = (o["alternatives"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        val conf = (o["confidence"] as? JsonPrimitive)?.content?.toFloatOrNull()?.coerceIn(0f, 1f) ?: if (word.isEmpty()) 0f else 0.5f
        return WordHint(word, alts, conf)
    }

    fun parseTranscript(text: String): TranscriptResult? {
        val o = extractJsonObject(text) ?: return null
        val t = o.str("text") ?: o.str("heard") ?: return null
        return TranscriptResult(o.str("lang")?.trim().orEmpty(), t.trim())
    }

    fun parseVoiceCard(text: String, l1: String, l2: String, confidence: Confidence): VoiceCard? {
        val o = extractJsonObject(text) ?: return null
        return VoiceCard.fromJson(o, l1, l2, confidence)
    }

    /** When nothing parses: the first non-empty line, trimmed to something a HUD can show. */
    fun rawFallback(text: String, max: Int = 200): String =
        stripFences(text).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(max) ?: ""

    companion object {
        private val FENCE = Regex("```[a-zA-Z]*")
        private val TRUNCATION_REPAIRS = listOf("}", "\"}", "\"}]}", "}]}", "]}", "\"]}")

        fun stripFences(text: String): String = text.replace(FENCE, "").trim()

        /** Index of the brace matching the one at [start], string-aware; -1 if unbalanced. */
        fun matchingBrace(s: String, start: Int): Int {
            var depth = 0
            var inString = false
            var escape = false
            for (i in start until s.length) {
                val ch = s[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        ch == '\\' -> escape = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun JsonObject.str(key: String): String? = when (val v: JsonElement? = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            else -> null
        }
    }
}

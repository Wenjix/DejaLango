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

data class StageAResult(val speaker: Speaker, val lang: String, val heard: String, val heardL1: String, val repaired: Boolean = false, val roman: String? = null)

/** A JSON object pulled out of model text. [repaired] means the text was patched to parse, such as truncated output closed up. */
data class Extracted(val obj: JsonObject, val repaired: Boolean)

data class WordHint(val word: String, val alternatives: List<String>, val confidence: Float, val roman: String? = null, val meaning: String? = null, val lang: String = "")

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
        return extractFrom(stripped)
            // A 2B model sometimes opens a key it never writes (`"back":"…","},{…`); drop the dangling `,"` and retry.
            ?: DANGLING_KEY.replace(stripped, "$1").takeIf { it != stripped }?.let { extractFrom(it)?.copy(repaired = true) }
            // A copied legacy skeleton keeps its speaker alternatives; it is still subject to placeholder rejection.
            ?: SPEAKER_CHOICE.replace(stripped, "\"them\"").takeIf { it != stripped }?.let { extractFrom(it)?.copy(repaired = true) }
    }

    private fun extractFrom(stripped: String): Extracted? {
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
        fun field(key: String): String? = o.str(key)?.trim()?.takeUnless { PLACEHOLDER_ECHO.matches(it) }
        val heard = if (o.str("heard") == null) return null else field("heard").orEmpty()
        val speaker = when (o.str("speaker")?.lowercase()?.trim()) {
            "me", "wearer", "user" -> Speaker.ME
            "them", "other" -> Speaker.THEM
            else -> Speaker.THEM
        }
        return StageAResult(
            speaker = speaker,
            // A choice copied from a template ("en|zh") names no language: callers decide from the line's script, or ask
            // again when both languages share one.
            lang = field("lang")?.takeUnless { '|' in it }.orEmpty(),
            heard = heard,
            heardL1 = field("heard_l1") ?: field("heardL1") ?: field("translation") ?: heard,
            repaired = e.repaired,
            roman = o.str("roman")?.takeIf { it.isNotBlank() && it != "null" },
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
            // A value copied from the prompt's skeleton ("<plain in my voice>", "...") is not an answer.
            fun field(key: String): String? = r.str(key)?.trim()?.takeIf { it.isNotEmpty() && !PLACEHOLDER_ECHO.matches(it) }
            val plain = field("plain")
            val yours = field("yours")
            val any = yours ?: plain ?: return@mapNotNull null
            Reply(
                plain = plain ?: any,
                yours = yours ?: any,
                roman = field("roman")?.takeIf { it != "null" },
                back = field("back"),
            )
        }
    }

    fun parseWordHint(text: String): WordHint? {
        val o = extractJsonObject(text) ?: return null
        val word = o.str("word")?.trim() ?: return null
        val alts = (o["alternatives"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        val conf = (o["confidence"] as? JsonPrimitive)?.content?.toFloatOrNull()?.coerceIn(0f, 1f) ?: if (word.isEmpty()) 0f else 0.5f
        return WordHint(word, alts, conf, o.str("roman")?.takeIf { it.isNotBlank() && it != "null" }, o.str("meaning"), o.str("lang").orEmpty())
    }

    fun parseTranscript(text: String): TranscriptResult? {
        val o = extractJsonObject(text) ?: return null
        val t = (o.str("text") ?: o.str("heard") ?: return null).trim()
        // "<verbatim transcript>" copied from the prompt is no transcript.
        return TranscriptResult(o.str("lang")?.trim().orEmpty(), if (PLACEHOLDER_ECHO.matches(t)) "" else t)
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
        private val DANGLING_KEY = Regex(",\\s*\"?\\s*([}\\]])")
        private val SPEAKER_CHOICE = Regex("\"me\"\\s*\\|\\s*\"them\"")
        private val PLACEHOLDER_ECHO = Regex("<[^<>]*>|\\.{3}|…")

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

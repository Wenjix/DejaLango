package dev.dejalango.engine.playbook

import java.text.Normalizer
import java.util.Locale

/**
 * The playbook's fast path: read Stage A's "heard" while it streams and, on a confident plain-text match with a LOCAL
 * line, stop the generation before heard_l1 and roman, which a hit doesn't need. No embedder, so it runs inside the
 * collector. Anything less than confident leaves Stage A to finish and the embedding match to decide.
 */
object FastMatch {
    /** Edit similarity a near match needs, and how far it must lead every other moment's LOCAL lines. */
    const val BAR = 0.85
    const val MARGIN = 0.15

    /**
     * "heard"'s value once its closing quote has arrived in a streamed JSON [prefix]; null until then. Like parseStageA,
     * it reads only the first top-level object's own "heard", never a nested one.
     */
    fun closedHeard(prefix: CharSequence): String? {
        var lastString: String? = null // the string just read, which is a key if a colon follows
        var valueOf: String? = null // the key whose value comes next
        var depth = 0 // braces open around the scan
        var i = 0
        while (i < prefix.length) {
            when (prefix[i]) {
                '"' -> {
                    val (text, end) = readString(prefix, i + 1) ?: return null // not closed yet
                    if (valueOf == "heard" && depth == 1) return text
                    lastString = text; valueOf = null
                    i = end
                }
                ':' -> { valueOf = lastString; lastString = null }
                '{' -> { depth++; lastString = null; valueOf = null }
                '}' -> {
                    if (depth == 1) return null // the first object closed without one
                    if (depth > 0) depth--
                    lastString = null; valueOf = null
                }
                ',', '[', ']' -> { lastString = null; valueOf = null }
            }
            i++
        }
        return null
    }

    /** The decoded string starting after an opening quote at [from], with its closing quote's index; null if cut off. */
    private fun readString(s: CharSequence, from: Int): Pair<String, Int>? {
        val out = StringBuilder()
        var i = from
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return out.toString() to i
                c != '\\' -> out.append(c)
                i + 1 >= s.length -> return null
                else -> {
                    i++
                    when (val e = s[i]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 >= s.length) return null
                            val code = s.subSequence(i + 1, i + 5).toString().toIntOrNull(16) ?: return null
                            out.append(code.toChar()); i += 4
                        }
                        else -> out.append(e) // \" \\ \/
                    }
                }
            }
            i++
        }
        return null
    }

    /** NFKC, lower case, and no whitespace or punctuation, so a transcript's punctuation never decides a match. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.getType(it) in PUNCTUATION }

    /** 1 − edit distance / longer length, on normalized text: 1.0 is identical. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = maxOf(a.length, b.length)
        return if (longer == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / longer
    }

    /**
     * The LOCAL line [heard] matches with confidence, or null. An exact match after [normalize] hits unless another
     * moment's LOCAL line matches exactly too. A near match needs [bar] and a [margin] over every other moment's LOCAL
     * lines; phrasings of the same moment share cards, so they never compete. A WEARER line as close or closer always
     * means null: that is my line, not theirs.
     */
    fun <T> find(
        heard: String, lines: List<T>, text: (T) -> String, isLocal: (T) -> Boolean, momentOf: (T) -> Any,
        bar: Double = BAR, margin: Double = MARGIN,
    ): T? {
        val h = normalize(heard)
        if (h.isEmpty()) return null
        val scored = lines.map { it to similarity(h, normalize(text(it))) }
        val (best, score) = scored.filter { isLocal(it.first) }.maxByOrNull { it.second } ?: return null
        if (scored.any { !isLocal(it.first) && it.second >= score }) return null
        val rival = scored.filter { isLocal(it.first) && momentOf(it.first) != momentOf(best) }.maxOfOrNull { it.second } ?: 0.0
        return best.takeIf { if (score == 1.0) rival < 1.0 else score >= bar && score - rival >= margin }
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private val PUNCTUATION = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    ).map { it.toInt() }.toSet()
}

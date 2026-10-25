package dev.dejalango.engine.playbook

import java.text.Normalizer
import java.util.Locale

/**
 * The playbook's fast path: read Stage A's "heard" while it streams and, when it is exactly a LOCAL line, stop the
 * generation before heard_l1 and roman, which a hit doesn't need. No embedder, so it runs inside the collector. Only an
 * exact match may stop: the seed line's translation then stands for what was said, where a near one could differ in
 * the one word that matters ("尾号三八二八" against a seed's 3827). Anything else lets Stage A finish and the embedding
 * match decide.
 */
object FastMatch {
    /**
     * The first top-level object's own string properties whose closing quote has arrived in a streamed JSON [prefix],
     * as parseStageA reads them: nested objects and text around the object are skipped.
     */
    fun closedFields(prefix: CharSequence): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        var lastString: String? = null // the string just read, which is a key if a colon follows
        var valueOf: String? = null // the key whose value comes next
        var depth = 0 // braces open around the scan
        var i = 0
        while (i < prefix.length) {
            when (prefix[i]) {
                '"' -> {
                    val (text, end) = readString(prefix, i + 1) ?: return fields // not closed yet
                    if (valueOf != null && depth == 1) fields.putIfAbsent(valueOf, text)
                    lastString = text; valueOf = null
                    i = end
                }
                ':' -> { valueOf = lastString; lastString = null }
                '{' -> { depth++; lastString = null; valueOf = null }
                '}' -> {
                    if (depth == 1) return fields // the first object closed
                    if (depth > 0) depth--
                    lastString = null; valueOf = null
                }
                ',', '[', ']' -> { lastString = null; valueOf = null }
            }
            i++
        }
        return fields
    }

    /** "heard"'s value once its closing quote has arrived in a streamed JSON [prefix]; null until then. */
    fun closedHeard(prefix: CharSequence): String? = closedFields(prefix)["heard"]

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

    /**
     * The LOCAL line [heard] is, after [normalize], or null. Null too when the lines of more than one moment match, or
     * a WEARER line does: that is my line, not theirs.
     */
    fun <T> find(heard: String, lines: List<T>, text: (T) -> String, isLocal: (T) -> Boolean, momentOf: (T) -> Any): T? {
        val h = normalize(heard)
        if (h.isEmpty()) return null
        val same = lines.filter { normalize(text(it)) == h }
        if (same.any { !isLocal(it) } || same.map(momentOf).distinct().size != 1) return null
        return same.first()
    }

    private val PUNCTUATION = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    ).map { it.toInt() }.toSet()
}

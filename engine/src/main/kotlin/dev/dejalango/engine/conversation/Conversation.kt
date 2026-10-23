package dev.dejalango.engine.conversation

enum class Speaker { ME, THEM, UNKNOWN }

data class Turn(
    val id: Long,
    val speaker: Speaker,
    val lang: String,
    val text: String,
    val textL1: String?,
    val tMs: Long,
)

/**
 * The running two-party conversation. Single-writer (the TurnProcessor); readers get snapshots.
 * Bounded so the prompt window and memory stay small.
 */
class ConversationState(private val maxTurns: Int = 40) {
    private val turns = ArrayList<Turn>()
    private var nextId = 1L

    @Volatile
    var learnedToday: Int = 0
        private set

    /** Replies most recently suggested to ME, in L2 — matched against my next utterance. */
    @Volatile
    var lastSuggested: List<String> = emptyList()

    /** L1 text of the last THEM turn — the SINGLE pipeline shape retrieves exemplars from it. */
    @Volatile
    var lastHeardL1: String? = null

    @Synchronized
    fun add(speaker: Speaker, lang: String, text: String, textL1: String?, tMs: Long): Turn {
        val t = Turn(nextId++, speaker, lang, text, textL1, tMs)
        turns += t
        while (turns.size > maxTurns) turns.removeAt(0)
        if (speaker == Speaker.THEM) lastHeardL1 = textL1 ?: text
        return t
    }

    @Synchronized
    fun snapshot(): List<Turn> = ArrayList(turns)

    /** The last [n] turns, oldest first — what goes into prompts. */
    @Synchronized
    fun window(n: Int): List<Turn> = if (turns.size <= n) ArrayList(turns) else ArrayList(turns.subList(turns.size - n, turns.size))

    fun markReplyUsed() {
        learnedToday++
    }

    @Synchronized
    fun clear() {
        turns.clear()
        lastSuggested = emptyList()
        lastHeardL1 = null
        learnedToday = 0
    }
}

/**
 * Did what I just said match one of the suggested replies? Character-level normalized edit distance,
 * so it works for Chinese (no word boundaries) and English alike.
 */
object ReplyMatcher {
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    /** Index of the best candidate within [threshold] (normalized distance), or null. */
    fun match(spoken: String, candidates: List<String>, threshold: Double = 0.3): Int? {
        val a = normalize(spoken)
        if (a.isEmpty()) return null
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        candidates.forEachIndexed { i, c ->
            val b = normalize(c)
            if (b.isEmpty()) return@forEachIndexed
            val d = levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
            if (d < bestScore) {
                bestScore = d
                best = i
            }
        }
        return if (bestScore <= threshold) best else null
    }

    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

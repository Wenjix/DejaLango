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

/** A reply suggested to ME, in L2: the personalized line and its phrasebook version. Reading either one aloud counts. */
data class SuggestedReply(val yours: String, val plain: String)

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

    /**
     * The suggestion set on offer to ME, matched against my next utterance. Empty once it is consumed (a reply was
     * read aloud or confirmed) or dismissed, so cards that stay rendered on the HUD can never match again.
     */
    @Volatile
    var lastSuggested: List<SuggestedReply> = emptyList()
        private set

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

    /** The reply I just confirmed on the HUD. I usually say it next; that reading is absorbed once, not taken as a new line. */
    private var confirmedEcho: SuggestedReply? = null

    @Synchronized
    fun suggest(replies: List<SuggestedReply>) {
        lastSuggested = replies
        confirmedEcho = null
    }

    /** Takes the suggestion set on offer and leaves none, so each set is used at most once; empty if already taken. */
    @Synchronized
    fun takeSuggestions(): List<SuggestedReply> = lastSuggested.also { lastSuggested = emptyList() }

    /** [takeSuggestions] for a reply confirmed on the HUD, remembering it as the echo to expect; empty if already taken. */
    @Synchronized
    fun confirm(reply: SuggestedReply): List<SuggestedReply> = takeSuggestions().also { if (it.isNotEmpty()) confirmedEcho = reply }

    /** The confirmed reply, once: gone whether or not the next utterance turns out to be my reading of it. */
    @Synchronized
    fun takeEcho(): SuggestedReply? = confirmedEcho.also { confirmedEcho = null }

    /** The cards were cleared or a new line superseded them: nothing on offer, no echo to expect. */
    @Synchronized
    fun dismissSuggestions() {
        lastSuggested = emptyList()
        confirmedEcho = null
    }

    @Synchronized
    fun clear() {
        turns.clear()
        lastSuggested = emptyList()
        confirmedEcho = null
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

    /** Strict enough that what THEY say next is not mistaken for my reading of a suggestion. */
    const val THRESHOLD = 0.35

    /** Under this many normalized characters a reply allows about one slip: their echo of it sits within 0.35. */
    private const val SHORT_REPLY_CHARS = 10
    private const val SHORT_REPLY_THRESHOLD = 0.2

    /** A partial reading needs at least this many characters (CJK or letters) and half of the reply. */
    const val MIN_CONTAINED_CHARS = 4

    /**
     * Index of the closest candidate within [threshold] (normalized edit distance; tighter for short candidates), or
     * null. Reading the start of a reply also matches. THEM echoing a reply inside a longer line does not.
     */
    fun match(spoken: String, candidates: List<String>, threshold: Double = THRESHOLD): Int? {
        val a = normalize(spoken)
        if (a.isEmpty()) return null
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        candidates.forEachIndexed { i, c ->
            val b = normalize(c)
            if (b.isEmpty()) return@forEachIndexed
            val longer = maxOf(a.length, b.length)
            val ratio = levenshtein(a, b).toDouble() / longer
            val partial = a.length >= MIN_CONTAINED_CHARS && a.length * 2 >= b.length && b.startsWith(a)
            val d = if (partial) minOf(ratio, 0.15) else ratio
            val limit = if (longer < SHORT_REPLY_CHARS) minOf(threshold, SHORT_REPLY_THRESHOLD) else threshold
            if (d <= limit && d < bestScore) {
                bestScore = d
                best = i
            }
        }
        return best
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

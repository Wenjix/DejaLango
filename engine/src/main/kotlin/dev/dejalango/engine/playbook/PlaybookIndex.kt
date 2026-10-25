package dev.dejalango.engine.playbook

import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.TextEmbedder
import dev.dejalango.engine.voice.Vectors
import kotlinx.coroutines.yield
import java.util.Locale

/** Whose words a seeded line is: the local's, or the wearer's (a reply card or another way to answer). */
enum class LineKind { LOCAL, WEARER }

/** A seeded line in a [PlaybookIndex]: its text, whose it is, and the moment of the scene it belongs to. */
class IndexedLine(val scene: Scene, val moment: Moment, val id: String, val text: String, val kind: LineKind) {
    override fun toString(): String = "IndexedLine#$id[$kind] \"$text\""
}

/** Why a transcript matched no moment. */
enum class MissReason {
    /** The transcript was blank. */
    EMPTY_QUERY,

    /** The embedder is not the one that built the index, as when the app's falls back to hashing: its vectors don't compare. */
    EMBEDDER_CHANGED,

    /** The scene id names no scene of the playbook. */
    NO_SCENE,

    /** The nearest line is the wearer's own: an unmatched reading of a card, not a line of the local's to answer. */
    WEARER_LINE,

    /** Even the nearest line is too far away to trust. */
    BELOW_THRESHOLD,

    /** Another moment's local line is within the margin, so which moment this is would be a guess. */
    AMBIGUOUS,
}

/** What [PlaybookIndex.match] made of a transcript: the moment the local is at, or why there is none. */
sealed class PlaybookMatch {
    /** The nearest line's dot product with the query, both at unit length. */
    abstract val score: Float

    /** The best score among the local's lines of other moments: how close the next guess came. */
    abstract val runnerUp: Float

    /** One line for logcat. */
    abstract fun describe(): String

    final override fun toString(): String = describe()

    /** The local said [matchedText], or something near it, at [moment]: its reply cards answer them. */
    data class Hit(
        val scene: Scene,
        val moment: Moment,
        val matchedText: String,
        override val score: Float,
        override val runnerUp: Float,
    ) : PlaybookMatch() {
        override fun describe(): String = "hit ${moment.id} ${f3(score)} (next ${f3(runnerUp)}) 「$matchedText」"
    }

    /**
     * No moment, and why. [bestText] is the nearest line's; it is null, and the scores [NONE], when nothing was scored.
     * [bestLocal] is the best score among the local's lines of every moment in scope, the nearest line's own included:
     * for a [MissReason.WEARER_LINE], how far my answer stands from anything the local says.
     */
    data class Miss(
        val reason: MissReason,
        val bestText: String? = null,
        override val score: Float = NONE,
        override val runnerUp: Float = NONE,
        val bestLocal: Float = NONE,
    ) : PlaybookMatch() {
        override fun describe(): String {
            if (bestText == null) return "miss $reason"
            val local = if (reason == MissReason.WEARER_LINE) ", local ${f3(bestLocal)}" else ""
            return "miss $reason ${f3(score)} (next ${f3(runnerUp)}$local) 「$bestText」"
        }
    }

    companion object {
        /** A score or runner-up that isn't there: -1, the least a dot product of unit vectors can be. */
        const val NONE = -1f
    }
}

/**
 * Every seeded line of a [Playbook], embedded once, to tell which moment a transcript of the local's belongs to.
 * Immutable after [build]; [match] embeds with the same embedder, so it is only as thread-safe as that embedder.
 */
class PlaybookIndex private constructor(
    val playbook: Playbook,
    private val embedder: TextEmbedder,
    private val all: List<Entry>,
    /** What made the vectors, as the embedder described itself after embedding them. */
    val description: String,
) {
    /** A seeded line and its unit vector. */
    private class Entry(val line: IndexedLine, val vector: FloatArray)

    /** Every seeded line, in playbook order: each moment's local lines, then its replies and others. */
    val entries: List<IndexedLine> = all.map { it.line }

    val size: Int get() = all.size

    private val byScene: Map<String, List<Entry>> = all.groupBy { it.line.scene.id }

    /**
     * Which moment of [sceneId] (of every scene when null or blank) the local is at, from their transcript [query]. A hit's
     * nearest line is the local's, scores at least [threshold], and beats every other moment's local lines by [margin].
     * [query] is embedded with the index's embedder in the caller's context: the app calls this under CompanionProcessor's
     * model lock, on the model thread, because its embedder is not thread-safe.
     */
    suspend fun match(query: String, sceneId: String?, threshold: Float, margin: Float): PlaybookMatch {
        if (query.isBlank()) return PlaybookMatch.Miss(MissReason.EMPTY_QUERY)
        if (embedder.description != description) return PlaybookMatch.Miss(MissReason.EMBEDDER_CHANGED)
        val scope = if (sceneId.isNullOrBlank()) all else byScene[sceneId].orEmpty()
        if (scope.isEmpty()) return PlaybookMatch.Miss(MissReason.NO_SCENE)
        val q = unit(embedder.embed(query, EmbedRole.QUERY))
        // The app's embedder falls back inside embed(): then this query's vector came from another model than the index's.
        if (embedder.description != description) return PlaybookMatch.Miss(MissReason.EMBEDDER_CHANGED)
        val scores = FloatArray(scope.size) { dot(q, scope[it].vector) }
        // Of equal scores, the wearer's line, as it may be my reading of a card; else the first in playbook order.
        val b = scores.indices.maxWith(compareBy({ scores[it] }, { scope[it].line.kind == LineKind.WEARER }))
        val best = scope[b].line
        val score = scores[b]
        // Only other moments compete: the local's paraphrases within one moment agree on it.
        val runnerUp = scope.indices.filter { scope[it].line.kind == LineKind.LOCAL && scope[it].line.moment.id != best.moment.id }
            .maxOfOrNull { scores[it] } ?: PlaybookMatch.NONE
        // Every moment's, the nearest line's own included: a line as near their question as my answer to it could be either of ours.
        val bestLocal = scope.indices.filter { scope[it].line.kind == LineKind.LOCAL }.maxOfOrNull { scores[it] } ?: PlaybookMatch.NONE

        fun miss(reason: MissReason) = PlaybookMatch.Miss(reason, best.text, score, runnerUp, bestLocal)
        return when {
            best.kind == LineKind.WEARER -> miss(MissReason.WEARER_LINE)
            score < threshold -> miss(MissReason.BELOW_THRESHOLD)
            score - runnerUp < margin -> miss(MissReason.AMBIGUOUS)
            else -> PlaybookMatch.Hit(best.scene, best.moment, best.text, score, runnerUp)
        }
    }

    companion object {
        /**
         * Embeds every seeded line as a [EmbedRole.DOCUMENT], [batchSize] lines per call, yielding between calls: it runs
         * on the app's single model thread, where a turn must be able to go between batches. Each call runs once inside
         * [batchScope], where the app holds CompanionProcessor's model lock for that batch only.
         */
        suspend fun build(
            playbook: Playbook,
            embedder: TextEmbedder,
            batchSize: Int = 16,
            batchScope: suspend (embed: suspend () -> Unit) -> Unit = { it() },
        ): PlaybookIndex {
            require(batchSize >= 1) { "batchSize must be at least 1, got $batchSize" }
            val lines = playbook.scenes.flatMap { scene ->
                scene.moments.flatMap { m ->
                    m.local.map { IndexedLine(scene, m, it.id, it.text, LineKind.LOCAL) } +
                        (m.replies + m.others).map { IndexedLine(scene, m, it.id, it.text, LineKind.WEARER) }
                }
            }
            val all = ArrayList<Entry>(lines.size)
            val madeBy = LinkedHashSet<String>()
            for ((i, batch) in lines.chunked(batchSize).withIndex()) {
                if (i > 0) yield()
                val before = all.size
                batchScope {
                    val vectors = embedder.embed(batch.map { it.text }, EmbedRole.DOCUMENT)
                    check(vectors.size == batch.size) { "${embedder.description} returned ${vectors.size} vectors for ${batch.size} lines" }
                    for ((line, v) in batch.zip(vectors)) all += Entry(line, unit(v))
                    // Read after the call, which may load the model (renaming it) or fall back to hashing.
                    madeBy += embedder.description
                }
                check(all.size == before + batch.size) { "batchScope must run each batch exactly once, batch $i was not" }
            }
            // Vectors from two embedders don't compare: a description no embedder has makes every match a miss.
            val description = when (madeBy.size) {
                0 -> embedder.description
                1 -> madeBy.first()
                else -> "mixed: " + madeBy.joinToString(", then ")
            }
            return PlaybookIndex(playbook, embedder, all, description)
        }
    }
}

/** [v] at unit length. A zero vector stays zero, and so does a broken one (NaN, infinity) rather than win every match. */
private fun unit(v: FloatArray): FloatArray = if (v.all { it.isFinite() }) Vectors.normalize(v) else FloatArray(v.size)

/** Dot product over the shorter length: the cosine, for unit vectors. */
private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0.0
    for (i in 0 until minOf(a.size, b.size)) s += (a[i] * b[i]).toDouble()
    return s.toFloat()
}

/** Three decimals in any locale, so logs read the same on every phone. */
private fun f3(x: Float): String = "%.3f".format(Locale.ROOT, x)

package dev.dejalango.engine.voice

object Vectors {
    /** EmbeddingGemma 2 is Matryoshka-trained; 256 dims keeps the store small and cosine fast. */
    const val DIMS = 256

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += (x * x).toDouble()
        val inv = if (n > 0) (1.0 / Math.sqrt(n)).toFloat() else 0f
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] * inv
        return out
    }

    /** Cosine on possibly-unnormalized vectors; truncates to the shorter length. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until n) {
            dot += (a[i] * b[i]).toDouble(); na += (a[i] * a[i]).toDouble(); nb += (b[i] * b[i]).toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }

    fun truncate(v: FloatArray, dims: Int = DIMS): FloatArray = if (v.size <= dims) v else v.copyOf(dims)
}

class RetrievedExemplar(val exemplar: Exemplar, val score: Float)

/**
 * "Sentences of mine that are near what was just said." Cosine top-k with Maximal Marginal Relevance
 * so six exemplars are six different moves, not six paraphrases of one.
 */
class Retriever(private val store: ExemplarStore, private val embedder: TextEmbedder) {

    suspend fun forQuery(query: String, lang: String, k: Int = 6, lambda: Float = 0.7f): List<RetrievedExemplar> {
        if (k <= 0 || query.isBlank()) return emptyList()
        val pool = store.all(lang)
        if (pool.isEmpty()) return emptyList()
        val q = embedder.embed(query, EmbedRole.QUERY)
        return mmr(q, pool, k, lambda)
    }

    companion object {
        fun mmr(query: FloatArray, pool: List<Exemplar>, k: Int, lambda: Float): List<RetrievedExemplar> {
            val sims = FloatArray(pool.size) { Vectors.cosine(query, pool[it].embedding) }
            val chosen = ArrayList<Int>(k)
            val remaining = pool.indices.toMutableList()
            while (chosen.size < k && remaining.isNotEmpty()) {
                var bestIdx = -1
                var bestScore = Float.NEGATIVE_INFINITY
                for (i in remaining) {
                    var maxSimToChosen = 0f
                    for (c in chosen) maxSimToChosen = maxOf(maxSimToChosen, Vectors.cosine(pool[i].embedding, pool[c].embedding))
                    val score = lambda * sims[i] - (1 - lambda) * maxSimToChosen
                    if (score > bestScore) {
                        bestScore = score; bestIdx = i
                    }
                }
                chosen += bestIdx
                remaining.remove(bestIdx)
            }
            return chosen.map { RetrievedExemplar(pool[it], sims[it]) }
        }
    }
}

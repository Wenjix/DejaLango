package dev.dejalango.engine.voice

enum class Source { TRANSCRIPT, PICKED_REPLY, MANUAL }

/** One sentence of mine, with its embedding (Matryoshka-truncated to [Vectors.DIMS]). */
class Exemplar(
    val id: Long,
    val text: String,
    val lang: String,
    val source: Source,
    val embedding: FloatArray,
) {
    override fun toString(): String = "Exemplar#$id[$lang/$source] \"$text\""
}

interface ExemplarStore {
    suspend fun add(text: String, lang: String, source: Source, embedding: FloatArray): Exemplar
    suspend fun all(lang: String): List<Exemplar>
    suspend fun count(): Int
    suspend fun wipe()
}

/** Sentence → vector. EmbeddingGemma 2 on-device in the app; a hashing fake in tests. */
interface TextEmbedder {
    val dims: Int
    suspend fun embed(texts: List<String>): List<FloatArray>
    suspend fun embed(text: String): FloatArray = embed(listOf(text)).first()
}

/** In-memory store: tests, the judge's ephemeral quick corpus, and a fallback when Room is unavailable. */
class InMemoryExemplarStore : ExemplarStore {
    private val lock = Any()
    private val items = ArrayList<Exemplar>()
    private var nextId = 1L

    override suspend fun add(text: String, lang: String, source: Source, embedding: FloatArray): Exemplar = synchronized(lock) {
        val e = Exemplar(nextId++, text, lang, source, embedding)
        items += e
        e
    }

    override suspend fun all(lang: String): List<Exemplar> = synchronized(lock) { items.filter { it.lang.equals(lang, ignoreCase = true) } }

    override suspend fun count(): Int = synchronized(lock) { items.size }

    override suspend fun wipe() {
        synchronized(lock) { items.clear() }
    }
}

/**
 * Deterministic bag-of-character-trigrams embedding. Not semantic — but near-duplicate sentences land
 * near each other, which is all tests and the fake pipeline need.
 */
class HashingEmbedder(override val dims: Int = Vectors.DIMS) : TextEmbedder {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { embedOne(it) }

    private fun embedOne(text: String): FloatArray {
        val v = FloatArray(dims)
        val s = "  " + text.lowercase() + "  "
        for (i in 0 until s.length - 2) {
            val h = s.substring(i, i + 3).hashCode()
            val idx = Math.floorMod(h, dims)
            v[idx] += if ((h ushr 31) == 0) 1f else -1f
        }
        return Vectors.normalize(v)
    }
}

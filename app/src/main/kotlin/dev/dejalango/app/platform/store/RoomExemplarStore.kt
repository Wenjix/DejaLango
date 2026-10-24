package dev.dejalango.app.platform.store

import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.Exemplar
import dev.dejalango.engine.voice.ExemplarStore
import dev.dejalango.engine.voice.Source
import dev.dejalango.engine.voice.VoiceCard
import dev.dejalango.engine.config.Language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room-backed exemplar store with an in-memory mirror per language, so retrieval never touches
 * SQLite on the hot path. Embeddings are stored as little-endian float32 blobs.
 */
class RoomExemplarStore(private val db: DejaLangoDb) : ExemplarStore {
    private val lock = Any()
    private val cache = HashMap<String, MutableList<Exemplar>>()
    private val loaded = HashSet<String>()

    override suspend fun add(text: String, lang: String, source: Source, embedding: FloatArray): Exemplar {
        val key = lang.lowercase()
        val id = db.exemplars().insert(ExemplarEntity(text = text, lang = key, source = source.name, embedding = encode(embedding), createdAt = System.currentTimeMillis()))
        val e = Exemplar(id, text, key, source, embedding)
        synchronized(lock) { if (key in loaded) cache.getOrPut(key) { ArrayList() }.add(e) }
        return e
    }

    override suspend fun all(lang: String): List<Exemplar> {
        val key = lang.lowercase()
        synchronized(lock) { if (key in loaded) return ArrayList(cache[key] ?: emptyList()) }
        val rows = db.exemplars().byLang(key).map { Exemplar(it.id, it.text, it.lang, runCatching { Source.valueOf(it.source) }.getOrDefault(Source.TRANSCRIPT), decode(it.embedding)) }
        synchronized(lock) {
            cache[key] = rows.toMutableList()
            loaded += key
            return ArrayList(rows)
        }
    }

    override suspend fun count(): Int = db.exemplars().count()

    override suspend fun wipe() {
        db.exemplars().wipe()
        db.voiceCards().wipe()
        synchronized(lock) { cache.clear(); loaded.clear() }
    }

    suspend fun loadVoiceCard(key: String = DEFAULT_CARD): VoiceCard? {
        fun decode(row: VoiceCardEntity?): VoiceCard? = row?.let { runCatching { VoiceCard.fromJson(Json.parseToJsonElement(it.json) as JsonObject) }.getOrNull() }
        if (key == DEFAULT_CARD) return decode(db.voiceCards().get(key))
        val canonical = Language.byCode(key).code
        decode(db.voiceCards().get("l1:$canonical"))?.let { return it }
        val legacy = decode(db.voiceCards().get(DEFAULT_CARD)) ?: return null
        if (Language.byCode(legacy.l1).code != canonical) return null
        db.voiceCards().put(VoiceCardEntity("l1:$canonical", legacy.toJson().toString(), System.currentTimeMillis()))
        return legacy
    }

    /** Only FULL cards are persisted; a judge's QUICK card stays in memory. */
    suspend fun saveVoiceCard(card: VoiceCard, key: String = "l1:${Language.byCode(card.l1).code}") {
        if (card.confidence != Confidence.FULL) return
        db.voiceCards().put(VoiceCardEntity(key, card.toJson().toString(), System.currentTimeMillis()))
    }

    companion object {
        const val DEFAULT_CARD = "default"

        fun encode(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) bb.putFloat(x)
            return bb.array()
        }

        fun decode(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat(it * 4) }
        }
    }
}

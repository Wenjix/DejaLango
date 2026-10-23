package dev.dejalango.app.platform.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "exemplars")
data class ExemplarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val lang: String,
    val source: String,
    val embedding: ByteArray, // float32 little-endian, Vectors.DIMS long
    val createdAt: Long,
)

@Entity(tableName = "voice_cards")
data class VoiceCardEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface ExemplarDao {
    @Insert
    suspend fun insert(e: ExemplarEntity): Long

    @Query("SELECT * FROM exemplars WHERE lang = :lang ORDER BY id")
    suspend fun byLang(lang: String): List<ExemplarEntity>

    @Query("SELECT COUNT(*) FROM exemplars")
    suspend fun count(): Int

    @Query("DELETE FROM exemplars")
    suspend fun wipe()
}

@Dao
interface VoiceCardDao {
    @Query("SELECT * FROM voice_cards WHERE `key` = :key")
    suspend fun get(key: String): VoiceCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: VoiceCardEntity)

    @Query("DELETE FROM voice_cards")
    suspend fun wipe()
}

@Database(entities = [ExemplarEntity::class, VoiceCardEntity::class], version = 1, exportSchema = true)
abstract class DejaLangoDb : RoomDatabase() {
    abstract fun exemplars(): ExemplarDao
    abstract fun voiceCards(): VoiceCardDao

    companion object {
        fun open(context: Context): DejaLangoDb =
            Room.databaseBuilder(context.applicationContext, DejaLangoDb::class.java, "dejalango.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

package dev.dejalango.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.app.platform.store.*
import dev.dejalango.engine.voice.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceOwnershipAndroidTest {
    @Test fun legacyOwnerCardIsCopiedOnlyIntoItsL1AndQuickCardsNeverPersist() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DejaLangoDb::class.java).build()
        try {
            val store = RoomExemplarStore(db)
            val english = VoiceCard("en", "zh", notes = "Owner words", confidence = Confidence.FULL)
            db.voiceCards().put(VoiceCardEntity("default", english.toJson().toString(), 1))
            assertEquals(english, store.loadVoiceCard("en-GB"))
            assertNotNull(db.voiceCards().get("default"))
            assertNotNull(db.voiceCards().get("l1:en"))
            assertNull(store.loadVoiceCard("es-CO"))
            store.saveVoiceCard(VoiceCard("es", "en", notes = "Quick", confidence = Confidence.QUICK))
            assertNull(store.loadVoiceCard("es"))
            val traditional = VoiceCard("zh-Hant", "en", notes = "Traditional owner", confidence = Confidence.FULL)
            store.saveVoiceCard(traditional)
            assertEquals(traditional, store.loadVoiceCard("zh-TW"))
            assertNull(store.loadVoiceCard("zh-CN"))
        } finally { db.close() }
    }
}

package dev.dejalango.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.dejalango.app.platform.config.ConfigStore
import dev.dejalango.engine.config.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigStoreTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val loaded = CompletableDeferred<Unit>()
        val saved = MutableStateFlow(initial)
        var failWrite = false
        override val data: Flow<Preferences> = flow { loaded.await(); emitAll(saved) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrite) throw IOException("unavailable")
            return transform(saved.value).also { saved.value = it }
        }
    }

    @Test fun `edits queued before disk load transform committed settings in order`() = runTest {
        val disk = Disk(preferencesOf(stringPreferencesKey("l1") to "en-GB", stringPreferencesKey("l2") to "es-MX"))
        val store = ConfigStore(disk, backgroundScope)
        store.update { it.copy(l2 = Language.ZH_TW) }
        store.update { it.copy(catchForgottenTaps = false) }
        store.updateSettings { it.copy(coachingSeen = true) }
        runCurrent(); assertFalse(store.ready.value)
        disk.loaded.complete(Unit); runCurrent()
        assertTrue(store.ready.value)
        assertEquals(Language.EN_GB, store.config.value.l1)
        assertEquals(Language.ZH_TW, store.config.value.l2)
        assertFalse(store.config.value.catchForgottenTaps)
        assertTrue(store.settings.value.coachingSeen)
        assertFalse(store.settings.value.useFakeModel)
    }

    @Test fun `failed save keeps committed pair and surfaces recovery without publishing false success`() = runTest {
        val disk = Disk(); disk.loaded.complete(Unit)
        val store = ConfigStore(disk, backgroundScope); runCurrent()
        disk.failWrite = true
        store.update { it.copy(l2 = Language.ES) }; runCurrent()
        assertEquals(Language.ZH, store.config.value.l2)
        assertNotNull(store.error.value)
        disk.failWrite = false
        store.update { it.copy(l2 = Language.ES) }; runCurrent()
        assertEquals(Language.ES, store.config.value.l2)
        assertNull(store.error.value)
    }
}

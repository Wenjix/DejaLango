package dev.dejalango.app.platform.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dejalango")

/** App-level switches that are not part of the engine's [PipelineConfig]. */
data class AppSettings(
    val useFakeModel: Boolean = true,
    val replayFixture: String? = null,     // asset name under fixtures/, or null for the microphone
    val hudPort: Int = 8765,
    val hudOnLan: Boolean = false,
)

/**
 * Persisted knobs. Reads are a StateFlow (the pipeline observes it; changes apply on the next turn);
 * writes go through [update] / [updateSettings] and are written through to DataStore.
 */
class ConfigStore(private val context: Context, private val scope: CoroutineScope) {
    private val _config = MutableStateFlow(PipelineConfig())
    val config: StateFlow<PipelineConfig> = _config.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            val p = context.dataStore.data.first()
            _config.value = p.toConfig()
            _settings.value = p.toSettings()
        }
    }

    fun update(transform: (PipelineConfig) -> PipelineConfig) {
        _config.update(transform)
        val c = _config.value
        scope.launch { context.dataStore.edit { it.write(c) } }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        val s = _settings.value
        scope.launch { context.dataStore.edit { it.write(s) } }
    }

    private fun Preferences.toConfig(): PipelineConfig {
        val d = PipelineConfig()
        return PipelineConfig(
            mode = this[K.mode]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: d.mode,
            l1 = this[K.l1]?.let { Language.byCode(it) } ?: d.l1,
            l2 = this[K.l2]?.let { Language.byCode(it) } ?: d.l2,
            shape = this[K.shape]?.let { runCatching { PipelineShape.valueOf(it) }.getOrNull() } ?: d.shape,
            backend = this[K.backend]?.let { runCatching { Backend.valueOf(it) }.getOrNull() } ?: d.backend,
            replies = this[K.replies] ?: d.replies,
            maxReplyWords = this[K.maxReplyWords] ?: d.maxReplyWords,
            maxReplyChars = this[K.maxReplyChars] ?: d.maxReplyChars,
            exemplarsK = this[K.exemplarsK] ?: d.exemplarsK,
            contextTurns = this[K.contextTurns] ?: d.contextTurns,
            generationTimeoutMs = this[K.timeoutMs] ?: d.generationTimeoutMs,
            thinking = this[K.thinking] ?: d.thinking,
            speakReplies = this[K.speakReplies] ?: d.speakReplies,
            structuredOutput = this[K.structuredOutput] ?: d.structuredOutput,
        )
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        useFakeModel = this[K.useFakeModel] ?: true,
        replayFixture = this[K.replayFixture]?.takeIf { it.isNotEmpty() },
        hudPort = this[K.hudPort] ?: 8765,
        hudOnLan = this[K.hudOnLan] ?: false,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.write(c: PipelineConfig) {
        this[K.mode] = c.mode.name
        this[K.l1] = c.l1.code
        this[K.l2] = c.l2.code
        this[K.shape] = c.shape.name
        this[K.backend] = c.backend.name
        this[K.replies] = c.replies
        this[K.maxReplyWords] = c.maxReplyWords
        this[K.maxReplyChars] = c.maxReplyChars
        this[K.exemplarsK] = c.exemplarsK
        this[K.contextTurns] = c.contextTurns
        this[K.timeoutMs] = c.generationTimeoutMs
        this[K.thinking] = c.thinking
        this[K.speakReplies] = c.speakReplies
        this[K.structuredOutput] = c.structuredOutput
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.useFakeModel] = s.useFakeModel
        this[K.replayFixture] = s.replayFixture ?: ""
        this[K.hudPort] = s.hudPort
        this[K.hudOnLan] = s.hudOnLan
    }

    private object K {
        val mode = stringPreferencesKey("mode")
        val l1 = stringPreferencesKey("l1")
        val l2 = stringPreferencesKey("l2")
        val shape = stringPreferencesKey("shape")
        val backend = stringPreferencesKey("backend")
        val replies = intPreferencesKey("replies")
        val maxReplyWords = intPreferencesKey("maxReplyWords")
        val maxReplyChars = intPreferencesKey("maxReplyChars")
        val exemplarsK = intPreferencesKey("exemplarsK")
        val contextTurns = intPreferencesKey("contextTurns")
        val timeoutMs = longPreferencesKey("timeoutMs")
        val thinking = booleanPreferencesKey("thinking")
        val speakReplies = booleanPreferencesKey("speakReplies")
        val structuredOutput = booleanPreferencesKey("structuredOutput")
        val useFakeModel = booleanPreferencesKey("useFakeModel")
        val replayFixture = stringPreferencesKey("replayFixture")
        val hudPort = intPreferencesKey("hudPort")
        val hudOnLan = booleanPreferencesKey("hudOnLan")
    }
}

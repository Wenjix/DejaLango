package dev.dejalango.app.platform.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dejalango.engine.config.AudioBackend
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.LanguagePair
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.config.PipelineShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dejalango")

/** App-level switches that are not part of the engine's [PipelineConfig]. */
data class AppSettings(
    val useFakeModel: Boolean = false,
    val replayFixture: String? = null,     // asset name under fixtures/, or null for the microphone
    val hudPort: Int = 8765,
    val hudOnLan: Boolean = false,
    val coachingSeen: Boolean = false,
)

/**
 * Persisted knobs. Reads are a StateFlow (the pipeline observes it; changes apply on the next turn);
 * writes go through [update] / [updateSettings] and are written through to DataStore.
 */
class ConfigStore(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(context.dataStore, scope)
    private val _config = MutableStateFlow(PipelineConfig())
    val config: StateFlow<PipelineConfig> = _config.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private sealed interface Change {
        data class Config(val transform: (PipelineConfig) -> PipelineConfig) : Change
        data class Settings(val transform: (AppSettings) -> AppSettings) : Change
    }
    private val changes = Channel<Change>(Channel.UNLIMITED)
    private var writer: Job? = null

    init { retryLoad() }

    fun retryLoad() {
        if (writer?.isActive == true) return
        writer = scope.launch {
            try {
                publish(store.data.first())
                _ready.value = true
                for (change in changes) {
                    try {
                        val committed = store.edit { p ->
                            when (change) {
                                is Change.Config -> {
                                    val next = change.transform(p.toConfig())
                                    require(LanguagePair.isValid(next.l1, next.l2))
                                    p.write(next)
                                }
                                is Change.Settings -> p.write(change.transform(p.toSettings()))
                            }
                        }
                        publish(committed)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _error.value = "Couldn't save that setting. Try the change again."
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = "Couldn't load your settings. Retry to continue."
            }
        }
    }

    fun update(transform: (PipelineConfig) -> PipelineConfig) {
        changes.trySend(Change.Config(transform))
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        changes.trySend(Change.Settings(transform))
    }

    suspend fun awaitReady() { ready.first { it } }

    private fun publish(p: Preferences) {
        _config.value = p.toConfig()
        _settings.value = p.toSettings()
        _error.value = null
    }

    private fun Preferences.toConfig(): PipelineConfig {
        val d = PipelineConfig()
        val pair = LanguagePair.restored(this[K.l1], this[K.l2])
        return PipelineConfig(
            l1 = pair.me,
            l2 = pair.them,
            shape = this[K.shape]?.let { runCatching { PipelineShape.valueOf(it) }.getOrNull() } ?: d.shape,
            backend = this[K.backend]?.let { runCatching { Backend.valueOf(it) }.getOrNull() } ?: d.backend,
            audioBackend = this[K.audioBackend]?.let { runCatching { AudioBackend.valueOf(it) }.getOrNull() } ?: d.audioBackend,
            replies = this[K.replies] ?: d.replies,
            maxReplyWords = this[K.maxReplyWords] ?: d.maxReplyWords,
            maxReplyChars = this[K.maxReplyChars] ?: d.maxReplyChars,
            exemplarsK = this[K.exemplarsK] ?: d.exemplarsK,
            contextTurns = this[K.contextTurns] ?: d.contextTurns,
            generationTimeoutMs = (this[K.timeoutMs] ?: d.generationTimeoutMs).coerceAtLeast(d.generationTimeoutMs),
            thinking = this[K.thinking] ?: d.thinking,
            speakReplies = this[K.speakReplies] ?: d.speakReplies,
            structuredOutput = this[K.structuredOutput] ?: d.structuredOutput,
            catchForgottenTaps = this[K.catchForgottenTaps] ?: d.catchForgottenTaps,
            playbookScene = this[K.playbookScene] ?: d.playbookScene,
            playbookThreshold = this[K.playbookThreshold] ?: d.playbookThreshold,
            playbookMargin = this[K.playbookMargin] ?: d.playbookMargin,
            playbookFastPath = this[K.playbookFastPath] ?: d.playbookFastPath,
        )
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        useFakeModel = this[K.useFakeModel] ?: false,
        replayFixture = this[K.replayFixture]?.takeIf { it.isNotEmpty() },
        hudPort = this[K.hudPort] ?: 8765,
        hudOnLan = this[K.hudOnLan] ?: false,
        coachingSeen = this[K.coachingSeen] ?: false,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.write(c: PipelineConfig) {
        remove(K.mode)
        this[K.l1] = c.l1.tag
        this[K.l2] = c.l2.tag
        this[K.shape] = c.shape.name
        this[K.backend] = c.backend.name
        this[K.audioBackend] = c.audioBackend.name
        this[K.replies] = c.replies
        this[K.maxReplyWords] = c.maxReplyWords
        this[K.maxReplyChars] = c.maxReplyChars
        this[K.exemplarsK] = c.exemplarsK
        this[K.contextTurns] = c.contextTurns
        this[K.timeoutMs] = c.generationTimeoutMs
        this[K.thinking] = c.thinking
        this[K.speakReplies] = c.speakReplies
        this[K.structuredOutput] = c.structuredOutput
        this[K.catchForgottenTaps] = c.catchForgottenTaps
        this[K.playbookScene] = c.playbookScene
        this[K.playbookThreshold] = c.playbookThreshold
        this[K.playbookMargin] = c.playbookMargin
        this[K.playbookFastPath] = c.playbookFastPath
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.useFakeModel] = s.useFakeModel
        this[K.replayFixture] = s.replayFixture ?: ""
        this[K.hudPort] = s.hudPort
        this[K.hudOnLan] = s.hudOnLan
        this[K.coachingSeen] = s.coachingSeen
    }

    private object K {
        val mode = stringPreferencesKey("mode")
        val l1 = stringPreferencesKey("l1")
        val l2 = stringPreferencesKey("l2")
        val shape = stringPreferencesKey("shape")
        val backend = stringPreferencesKey("backend")
        val audioBackend = stringPreferencesKey("audioBackend")
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
        val catchForgottenTaps = booleanPreferencesKey("catchForgottenTaps")
        val playbookScene = stringPreferencesKey("playbookScene")
        val playbookThreshold = floatPreferencesKey("playbookThreshold")
        val playbookMargin = floatPreferencesKey("playbookMargin")
        val playbookFastPath = booleanPreferencesKey("playbookFastPath")
        val coachingSeen = booleanPreferencesKey("coachingSeen")
    }
}

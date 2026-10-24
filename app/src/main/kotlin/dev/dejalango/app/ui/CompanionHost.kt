package dev.dejalango.app.ui

import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dejalango.app.BuildConfig
import dev.dejalango.app.di.AppGraph
import dev.dejalango.app.service.ListenService
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.VoiceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

@Composable
internal fun CompanionHost(service: ListenService?, graph: AppGraph, permissionError: String?, onStart: () -> Unit, onStop: () -> Unit) {
    val runtimeFlow = remember(service) { service?.runtimeFlow ?: MutableStateFlow(null) }
    val runtime by runtimeFlow.collectAsStateWithLifecycle()
    val config by graph.configStore.config.collectAsStateWithLifecycle()
    val settings by graph.configStore.settings.collectAsStateWithLifecycle()
    val ready by graph.configStore.ready.collectAsStateWithLifecycle()
    val settingsError by graph.configStore.error.collectAsStateWithLifecycle()
    val startingFlow = remember(service) { service?.starting ?: MutableStateFlow(false) }
    val starting by startingFlow.collectAsStateWithLifecycle()
    val errorFlow = remember(service) { service?.error ?: MutableStateFlow(null) }
    val serviceError by errorFlow.collectAsStateWithLifecycle()
    val hudFlow = remember(runtime, config.pair) { runtime?.hud ?: MutableStateFlow(HudState(phase = Phase.IDLE, pair = config.pair)) }
    val state by hudFlow.collectAsStateWithLifecycle()
    var storedCard by remember(config.l1.code) { mutableStateOf<VoiceCard?>(null) }
    LaunchedEffect(config.l1.code, state.voiceCard, ready) {
        if (ready) storedCard = withContext(Dispatchers.IO) { graph.exemplarStore.loadVoiceCard(config.l1.code) }
    }
    val view = LocalView.current
    var previousClosed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.capture.closedTurnId) {
        val id = state.capture.closedTurnId
        if (id != null && id != previousClosed) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        previousClosed = id
    }
    var developer by rememberSaveable { mutableStateOf(false) }
    var bench by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(developer || bench) { developer = false; bench = false }
    when {
        bench -> Column {
            TextButton(onClick = { bench = false }, modifier = Modifier.statusBarsPadding()) { Text("Back to Companion") }
            Box(Modifier.weight(1f)) { BenchScreen(service, graph, onStart, onStop) }
        }
        developer -> Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Developer tools", style = MaterialTheme.typography.headlineMedium)
            Text("The walkthrough uses synthetic fixtures and never starts audio or inference.")
            Button(onClick = { context.startActivity(Intent().setClassName(context, "dev.dejalango.app.ui.CompanionWalkthroughActivity")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open synthetic walkthrough") }
            OutlinedButton(onClick = { developer = false; bench = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open developer bench") }
            TextButton(onClick = { developer = false }) { Text("Back to Companion") }
        }
        else -> CompanionScreen(
            state = state, configuredPair = config.pair, ready = ready, starting = starting,
            error = permissionError ?: settingsError ?: serviceError,
            catchForgottenTaps = config.catchForgottenTaps, coachingSeen = settings.coachingSeen,
            card = state.voiceCard?.takeIf { it.l1 == config.l1.code } ?: storedCard,
            onAction = { action -> runtime?.onAction(action) },
            onListen = { if (runtime?.hud?.value?.modelReady == true) runtime?.startAudio() else onStart() },
            onStopListening = { runtime?.stopAudio() },
            onPair = { pair -> graph.configStore.update { it.copy(l1 = pair.me, l2 = pair.them) } },
            onCatch = { enabled -> graph.configStore.update { it.copy(catchForgottenTaps = enabled) } },
            onCoached = { graph.configStore.updateSettings { it.copy(coachingSeen = true) } },
            onBuildVoice = { sentences ->
                runCatching { runtime?.buildFullCard(sentences) }.fold(
                    onSuccess = { card -> when {
                        card == null -> "Start listening to load the local model first."
                        card.confidence == Confidence.NONE -> "Couldn't find a voice profile in those words. Try a longer sample."
                        runtime?.savesVoice != true -> "Profile ready for this session. This developer model cannot save a real profile."
                        else -> "Your voice profile is saved on this phone."
                    } },
                    onFailure = { "Couldn't build your profile. Try again after the conversation." },
                )
            },
            onRetrySettings = graph.configStore::retryLoad,
            onDeveloper = if (BuildConfig.DEBUG) ({ developer = true }) else null,
        )
    }
}

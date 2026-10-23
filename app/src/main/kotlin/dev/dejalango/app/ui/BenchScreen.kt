package dev.dejalango.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dejalango.app.di.AppGraph
import dev.dejalango.app.service.ListenService
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The glass box: everything the glasses show, plus everything they hide — exemplars used, the voice
 * card, stage timings, model/backend/tok-s, and every knob. Judges watch this; the wearer wears the HUD.
 */
@Composable
fun BenchScreen(
    service: ListenService?,
    graph: AppGraph,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
) {
    val runtime = service?.takeIf { it.started }?.runtime
    val hudFlow = remember(runtime) { runtime?.hud ?: MutableStateFlow(HudState(phase = Phase.IDLE, statusLine = "not listening")) }
    val hud by hudFlow.collectAsStateWithLifecycle()
    val config by graph.configStore.config.collectAsStateWithLifecycle()
    val settings by graph.configStore.settings.collectAsStateWithLifecycle()
    val hudUrl by (runtime?.hudUrl ?: MutableStateFlow<String?>(null)).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showKnobs by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val act: (HudAction) -> Unit = { a -> runtime?.onAction(a) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: phase, mode, level
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DejaLango", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            PhaseDot(hud.phase)
            Text(hud.phase.name.lowercase(), color = Color.Gray)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(60.dp).height(6.dp).background(Color(0xFF222222))) {
                Box(Modifier.fillMaxWidth(hud.level.coerceIn(0f, 1f)).height(6.dp).background(Color(0xFF5CFF8A)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mode.values().forEach { m ->
                FilterChip(selected = config.mode == m, onClick = { graph.configStore.update { it.copy(mode = m) } }, label = { Text(modeLabel(m)) })
            }
        }

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (runtime == null) Button(onClick = onStartListening) { Text("Start listening") }
            else OutlinedButton(onClick = onStopListening) { Text("Stop") }
            OutlinedButton(onClick = { act(HudAction.CutNow) }, enabled = runtime != null) { Text("Cut") }
            OutlinedButton(onClick = { act(HudAction.Clear) }, enabled = runtime != null) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Text(if (hud.showPlain) "phrasebook" else "you", color = Color.Gray, fontSize = 12.sp)
            Switch(checked = !hud.showPlain, onCheckedChange = { act(HudAction.TogglePlain) })
        }

        // Heard
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101418))) {
            Column(Modifier.padding(12.dp)) {
                Text("heard", color = Color.Gray, fontSize = 12.sp)
                Text(hud.heard?.text ?: if (hud.phase == Phase.LISTENING) "…" else "—", fontSize = 22.sp)
                hud.heard?.translation?.takeIf { it.isNotBlank() && it != hud.heard?.text }?.let { Text(it, color = Color(0xFFA9B0B8), fontSize = 16.sp) }
            }
        }

        // Replies or word hint
        if (config.mode == Mode.WORD_FINDING) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101418))) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hud.wordHint ?: if (hud.phase == Phase.THINKING) "…" else "—", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    if (hud.wordAlternatives.isNotEmpty()) Text(hud.wordAlternatives.joinToString("  ·  "), color = Color.Gray)
                }
            }
        } else {
            hud.replies.forEachIndexed { i, r ->
                val selected = hud.selectedReply == i
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { act(HudAction.Select(i)) },
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF14261A) else Color(0xFF101418)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (hud.showPlain) r.plain else r.yours, fontSize = 24.sp, color = if (selected) Color.White else Color(0xFFD0D5DA))
                        r.roman?.let { Text(it, color = Color(0xFFA9B0B8), fontSize = 15.sp) }
                        r.back?.let { Text("= $it", color = Color(0xFF8A9199), fontSize = 13.sp) }
                        if (!hud.showPlain && r.plain != r.yours) Text("phrasebook: ${r.plain}", color = Color(0xFF6E7680), fontSize = 12.sp)
                    }
                }
            }
            if (hud.replies.isEmpty() && hud.phase == Phase.THINKING) Text("…", fontSize = 24.sp, color = Color.Gray)
            if (hud.selectedReply != null) Button(onClick = { act(HudAction.Confirm) }) { Text("I said this one") }
        }

        hud.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
        hud.statusLine?.let { Text(it, color = Color.Gray) }

        // Glass box
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E11))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("glass box", color = Color.Gray, fontSize = 12.sp)
                hud.model?.let { m ->
                    Text("${m.name} · ${m.backend}" + (m.lastDecodeTokensPerSec?.let { " · %.1f tok/s".format(it) } ?: ""), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text("${config.l1.code}→${config.l2.code} · ${config.shape.name.lowercase()} · ${config.replies} replies · k=${config.exemplarsK} · learned ${hud.learnedToday}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                hud.trace?.let { Text(it.summary(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFA9B0B8)) }
                if (hud.exemplarsUsed.isNotEmpty()) {
                    Text("because you say:", color = Color.Gray, fontSize = 12.sp)
                    hud.exemplarsUsed.forEach { e -> Text("• ${e.text}  (%.2f)".format(e.score), fontSize = 13.sp) }
                }
                hud.voiceCard?.let { c -> Text("voice card: " + c.describe().replace("\n", " · "), fontSize = 12.sp, color = Color(0xFFA9B0B8)) }
                hudUrl?.let { url ->
                    Text("HUD: $url", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF5CFF8A))
                    Text("laptop: adb forward tcp:${settings.hudPort} tcp:${settings.hudPort}  then load the URL in hud.xyz", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showKnobs = !showKnobs }) { Text(if (showKnobs) "hide knobs" else "knobs") }
                    TextButton(
                        enabled = runtime != null && busy == null,
                        onClick = {
                            scope.launch {
                                busy = "building quick card from the last 60 s…"
                                runCatching { runtime?.buildQuickCardFromRecentAudio(60) }
                                busy = null
                            }
                        },
                    ) { Text("quick card (60 s)") }
                    TextButton(
                        enabled = runtime != null && busy == null,
                        onClick = { scope.launch { busy = "wiping…"; runCatching { runtime?.wipe() }; busy = null } },
                    ) { Text("wipe") }
                }
                busy?.let { Text(it, color = Color(0xFFFFD166), fontSize = 12.sp) }

                if (showKnobs) Knobs(graph)
            }
        }
    }
}

@Composable
private fun Knobs(graph: AppGraph) {
    val config by graph.configStore.config.collectAsStateWithLifecycle()
    val settings by graph.configStore.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KnobRow("fake model (restart listening to apply)") { Switch(checked = settings.useFakeModel, onCheckedChange = { v -> graph.configStore.updateSettings { it.copy(useFakeModel = v) } }) }
        KnobRow("HUD on LAN (default: localhost only)") { Switch(checked = settings.hudOnLan, onCheckedChange = { v -> graph.configStore.updateSettings { it.copy(hudOnLan = v) } }) }
        KnobRow("pipeline shape") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PipelineShape.values().forEach { s -> FilterChip(selected = config.shape == s, onClick = { graph.configStore.update { it.copy(shape = s) } }, label = { Text(s.name.lowercase()) }) }
            }
        }
        KnobRow("backend (restart to apply)") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Backend.values().forEach { b -> FilterChip(selected = config.backend == b, onClick = { graph.configStore.update { it.copy(backend = b) } }, label = { Text(b.name) }) }
            }
        }
        KnobRow("replies") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 3).forEach { n -> FilterChip(selected = config.replies == n, onClick = { graph.configStore.update { it.copy(replies = n) } }, label = { Text("$n") }) }
            }
        }
        KnobRow("exemplars k") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 6, 10).forEach { n -> FilterChip(selected = config.exemplarsK == n, onClick = { graph.configStore.update { it.copy(exemplarsK = n) } }, label = { Text("$n") }) }
            }
        }
        KnobRow("thinking") { Switch(checked = config.thinking, onCheckedChange = { v -> graph.configStore.update { it.copy(thinking = v) } }) }
        KnobRow("constrained JSON output (LiteRT-LM ResponseFormat)") { Switch(checked = config.structuredOutput, onCheckedChange = { v -> graph.configStore.update { it.copy(structuredOutput = v) } }) }
        KnobRow("speak chosen reply (TTS)") { Switch(checked = config.speakReplies, onCheckedChange = { v -> graph.configStore.update { it.copy(speakReplies = v) } }) }
        KnobRow("replay fixture (assets/fixtures)") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = settings.replayFixture == null, onClick = { graph.configStore.updateSettings { it.copy(replayFixture = null) } }, label = { Text("mic") })
                FilterChip(selected = settings.replayFixture == "demo_zh.wav", onClick = { graph.configStore.updateSettings { it.copy(replayFixture = "demo_zh.wav") } }, label = { Text("demo_zh.wav") })
            }
        }
    }
}

@Composable
private fun KnobRow(label: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun PhaseDot(phase: Phase) {
    val color = when (phase) {
        Phase.LISTENING -> Color(0xFF5CFF8A)
        Phase.THINKING -> Color(0xFFFFD166)
        Phase.SHOWING -> Color.White
        Phase.ERROR -> Color(0xFFFF6B6B)
        Phase.WARMING, Phase.IDLE -> Color.Gray
    }
    Box(Modifier.width(10.dp).height(10.dp).background(color, shape = androidx.compose.foundation.shape.CircleShape))
}

private fun modeLabel(m: Mode): String = when (m) {
    Mode.REPLY_OPTIONS -> "replies"
    Mode.SAY_IT_MY_WAY -> "say it my way"
    Mode.WORD_FINDING -> "word"
}

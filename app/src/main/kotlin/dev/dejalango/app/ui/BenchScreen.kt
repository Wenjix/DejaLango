package dev.dejalango.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.dejalango.app.service.PipelineRuntime
import dev.dejalango.engine.config.AudioBackend
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.PipelineShape
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.voice.VoiceCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The glass box: everything the glasses show, plus everything they hide — exemplars used, the voice
 * card, stage timings, model/backend/tok-s, and every knob. Judges watch this; the wearer wears the HUD.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchScreen(
    service: ListenService?,
    graph: AppGraph,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
) {
    val runtimeFlow = remember(service) { service?.runtimeFlow ?: MutableStateFlow(null) }
    val runtime by runtimeFlow.collectAsStateWithLifecycle()
    val hudFlow = remember(runtime) { runtime?.hud ?: MutableStateFlow(HudState(phase = Phase.IDLE, statusLine = "not listening")) }
    val hud by hudFlow.collectAsStateWithLifecycle()
    val config by graph.configStore.config.collectAsStateWithLifecycle()
    val hudUrl by (runtime?.hudUrl ?: MutableStateFlow<String?>(null)).collectAsStateWithLifecycle()
    val exemplarCountsFlow = remember(runtime) { runtime?.exemplarCounts ?: MutableStateFlow(PipelineRuntime.ExemplarCounts()) }
    val exemplarCounts by exemplarCountsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showKnobs by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) } // outcome of the last card/index action
    val act: (HudAction) -> Unit = { a -> runtime?.onAction(a) }
    val isListening = runtime != null

    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: phase, status, audio level meter
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DejaLango", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            PhaseDot(hud.phase, isListening)
            Text(phaseLabel(hud.phase, isListening), color = if (isListening) Color(0xFF5CFF8A) else Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("mic", color = Color.Gray, fontSize = 12.sp)
            Box(
                Modifier
                    .width(84.dp)
                    .height(10.dp)
                    .background(Color(0xFF1A1F24), shape = RoundedCornerShape(5.dp))
                    .border(1.dp, Color(0xFF3A424C), shape = RoundedCornerShape(5.dp)),
            ) {
                val barLevel = if (isListening) hud.level.coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth(barLevel)
                        .height(10.dp)
                        .background(Color(0xFF5CFF8A), shape = RoundedCornerShape(5.dp)),
                )
            }
        }

        // Mode selector
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("mode:", color = Color.Gray, fontSize = 12.sp)
                Mode.values().forEach { m ->
                    FilterChip(selected = config.mode == m, onClick = { graph.configStore.update { it.copy(mode = m) } }, label = { Text(modeLabel(m)) })
                }
            }
            Text(modeDescription(config.mode), color = Color(0xFF8A9199), fontSize = 11.sp)
        }

        // Listening & turn controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isListening) Button(onClick = onStartListening) { Text("Start listening") }
            else OutlinedButton(onClick = onStopListening) { Text("Stop") }
            OutlinedButton(onClick = { act(HudAction.CutNow) }, enabled = isListening) { Text("Cut turn") }
            OutlinedButton(onClick = { act(HudAction.Clear) }, enabled = isListening) { Text("Clear") }
        }

        // Phrasing toggle (You vs Phrasebook)
        if (config.mode != Mode.WORD_FINDING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("phrasing:", color = Color.Gray, fontSize = 12.sp)
                FilterChip(
                    selected = !hud.showPlain,
                    onClick = { if (hud.showPlain) act(HudAction.TogglePlain) },
                    enabled = isListening,
                    label = { Text("you (personalized)") },
                )
                FilterChip(
                    selected = hud.showPlain,
                    onClick = { if (!hud.showPlain) act(HudAction.TogglePlain) },
                    enabled = isListening,
                    label = { Text("phrasebook (plain)") },
                )
            }
        }

        // Heard
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101418), contentColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Text("heard", color = Color.Gray, fontSize = 12.sp)
                Text(
                    hud.heard?.text ?: when {
                        hud.phase == Phase.LISTENING -> "speaking…"
                        hud.phase == Phase.THINKING -> "transcribing…"
                        isListening -> "listening for speech…"
                        else -> "—"
                    },
                    fontSize = 22.sp,
                    color = Color.White,
                )
                hud.heard?.translation?.takeIf { it.isNotBlank() && it != hud.heard?.text }?.let { Text(it, color = Color(0xFFA9B0B8), fontSize = 16.sp) }
            }
        }

        // Replies or word hint
        if (config.mode == Mode.WORD_FINDING) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101418), contentColor = Color.White)) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hud.wordHint ?: if (hud.phase == Phase.THINKING) "…" else "—", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    if (hud.wordAlternatives.isNotEmpty()) Text(hud.wordAlternatives.joinToString("  ·  "), color = Color.Gray)
                }
            }
        } else {
            hud.replies.forEachIndexed { i, r ->
                val selected = hud.selectedReply == i
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { act(HudAction.Select(i)) },
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF14261A) else Color(0xFF101418), contentColor = Color.White),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val shown = if (hud.showPlain) r.plain else r.yours
                        Text(shown, fontSize = 24.sp, color = if (selected) Color.White else Color(0xFFD0D5DA))
                        // roman and back describe yours, so a phrasebook line that differs shows neither.
                        if (shown == r.yours) {
                            r.roman?.let { Text(it, color = Color(0xFFA9B0B8), fontSize = 15.sp) }
                            r.back?.let { Text("= $it", color = Color(0xFF8A9199), fontSize = 13.sp) }
                        }
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
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E11), contentColor = Color.White)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("glass box", color = Color.Gray, fontSize = 12.sp)
                hud.model?.let { m ->
                    Text("${m.name} · ${m.backend}" + (m.lastDecodeTokensPerSec?.let { " · %.1f tok/s".format(it) } ?: ""), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                }
                runtime?.let { Text(it.modelStatusLine, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF6E7680)) }
                Text("${config.l1.code}→${config.l2.code} · ${config.shape.name.lowercase()} · ${config.replies} replies · k=${config.exemplarsK} · learned ${hud.learnedToday}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD0D5DA))
                if (runtime != null) {
                    Text("exemplars indexed: ${exemplarCounts.total} · ${config.l1.code} for retrieval: ${exemplarCounts.l1}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD0D5DA))
                }
                hud.trace?.let { Text(it.summary(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFA9B0B8)) }
                if (hud.exemplarsUsed.isNotEmpty()) {
                    Text("because you say:", color = Color.Gray, fontSize = 12.sp)
                    hud.exemplarsUsed.forEach { e -> Text("• ${e.text}  (%.2f)".format(e.score), fontSize = 13.sp, color = Color(0xFFD0D5DA)) }
                }
                hud.voiceCard?.let { c -> Text("voice card: " + c.describe().replace("\n", " · "), fontSize = 12.sp, color = Color(0xFFA9B0B8)) }
                hudUrl?.let { url ->
                    Text("HUD: $url", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF5CFF8A))
                    Text("laptop: scripts/hud_forward.sh, then open the /sim URL it prints", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showKnobs = !showKnobs }) { Text(if (showKnobs) "hide knobs" else "knobs") }
                    TextButton(
                        enabled = runtime != null && busy == null,
                        onClick = {
                            scope.launch {
                                busy = "building quick card from the last 60 s…"
                                note = runCatching { runtime?.buildQuickCardFromRecentAudio(60) }
                                    .fold({ c -> cardNote("quick card", c, runtime, config.l1.code) }, { "quick card failed: ${it.message}" })
                                busy = null
                            }
                        },
                    ) { Text("quick card (60 s)") }
                    TextButton(
                        enabled = runtime != null && busy == null,
                        onClick = {
                            scope.launch {
                                busy = "seeding voice card & indexing exemplars…"
                                note = runCatching { runtime?.seedDefaultVoiceCard() }
                                    .fold({ c -> cardNote("seeded voice card", c, runtime, config.l1.code) }, { "seed voice failed: ${it.message}" })
                                busy = null
                            }
                        },
                    ) { Text("seed voice") }
                    TextButton(
                        enabled = runtime != null && busy == null,
                        onClick = {
                            scope.launch {
                                busy = "wiping…"
                                note = runCatching { runtime?.wipe() }.fold({ "wiped exemplars and voice card" }, { "wipe failed: ${it.message}" })
                                busy = null
                            }
                        },
                    ) { Text("wipe") }
                }
                busy?.let { Text(it, color = Color(0xFFFFD166), fontSize = 12.sp) }
                if (busy == null) note?.let { Text(it, color = Color(0xFFA9B0B8), fontSize = 12.sp) }

                if (showKnobs) Knobs(graph)
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Knobs(graph: AppGraph) {
    val config by graph.configStore.config.collectAsStateWithLifecycle()
    val settings by graph.configStore.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KnobRow("fake model (restart listening to apply)") { Switch(checked = settings.useFakeModel, onCheckedChange = { v -> graph.configStore.updateSettings { it.copy(useFakeModel = v) } }) }
        KnobRow("HUD on LAN (default: localhost only)") { Switch(checked = settings.hudOnLan, onCheckedChange = { v -> graph.configStore.updateSettings { it.copy(hudOnLan = v) } }) }
        KnobSection("pipeline shape") {
            PipelineShape.values().forEach { s ->
                FilterChip(
                    selected = config.shape == s,
                    onClick = { graph.configStore.update { it.copy(shape = s) } },
                    label = { Text(s.name.lowercase(), maxLines = 1, softWrap = false) },
                )
            }
        }
        KnobSection("backend (restart to apply)") {
            Backend.values().forEach { b ->
                FilterChip(
                    selected = config.backend == b,
                    onClick = { graph.configStore.update { it.copy(backend = b) } },
                    label = { Text(b.name, maxLines = 1, softWrap = false) },
                )
            }
        }
        KnobSection("audio encoder backend (restart to apply)") {
            AudioBackend.values().forEach { b ->
                FilterChip(
                    selected = config.audioBackend == b,
                    onClick = { graph.configStore.update { it.copy(audioBackend = b) } },
                    label = { Text(b.name.lowercase(), maxLines = 1, softWrap = false) },
                )
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
        KnobSection("replay fixture (assets/fixtures)") {
            FilterChip(selected = settings.replayFixture == null, onClick = { graph.configStore.updateSettings { it.copy(replayFixture = null) } }, label = { Text("mic", maxLines = 1, softWrap = false) })
            FilterChip(selected = settings.replayFixture == "demo_zh.wav", onClick = { graph.configStore.updateSettings { it.copy(replayFixture = "demo_zh.wav") } }, label = { Text("demo_zh.wav", maxLines = 1, softWrap = false) })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnobSection(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            chips()
        }
    }
}

@Composable
private fun PhaseDot(phase: Phase, isListening: Boolean) {
    val color = when {
        !isListening -> Color.Gray
        phase == Phase.LISTENING || phase == Phase.IDLE -> Color(0xFF5CFF8A)
        phase == Phase.THINKING || phase == Phase.WARMING -> Color(0xFFFFD166)
        phase == Phase.SHOWING -> Color.White
        phase == Phase.ERROR -> Color(0xFFFF6B6B)
        else -> Color.Gray
    }
    Box(Modifier.width(10.dp).height(10.dp).background(color, shape = CircleShape))
}

/** "seeded voice card: full · 40 en exemplars indexed" — the card's confidence says whether Gemma's card parsed. */
private fun cardNote(what: String, card: VoiceCard?, runtime: PipelineRuntime?, l1: String): String =
    "$what: ${card?.confidence?.name?.lowercase() ?: "—"}" + when {
        runtime == null -> ""
        !runtime.savesVoice -> " · fake model: in memory only, nothing saved"
        else -> " · ${runtime.exemplarCounts.value.l1} $l1 exemplars indexed"
    }

private fun phaseLabel(phase: Phase, isListening: Boolean): String = when {
    !isListening -> "stopped"
    phase == Phase.IDLE -> "listening"
    phase == Phase.LISTENING -> "speech detected"
    else -> phase.name.lowercase()
}

private fun modeLabel(m: Mode): String = when (m) {
    Mode.REPLY_OPTIONS -> "replies"
    Mode.SAY_IT_MY_WAY -> "say it my way"
    Mode.WORD_FINDING -> "word"
}

private fun modeDescription(m: Mode): String = when (m) {
    Mode.REPLY_OPTIONS -> "They speak → suggests replies in their language, phrased in your voice"
    Mode.SAY_IT_MY_WAY -> "You speak → translates into their language in your voice"
    Mode.WORD_FINDING -> "You pause mid-sentence → suggests the word you're reaching for"
}

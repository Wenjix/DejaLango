package dev.dejalango.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.LanguagePair
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.*
import org.json.JSONObject

/** Debug-only, authored fixtures. Never binds the listening service or touches user storage. */
class CompanionWalkthroughActivity : ComponentActivity() {
    var fixture by mutableIntStateOf(0)
    var beat by mutableIntStateOf(2)
    private lateinit var catalog: CompanionFixtures

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        catalog = CompanionFixtures(this)
        fixture = intent.getIntExtra("fixture", 0).coerceIn(0, 3)
        beat = intent.getIntExtra("beat", 2).coerceIn(0, 19)
        setContent {
            CompanionTheme {
                var displayPaused by remember { mutableStateOf(false) }
                var selected by remember(fixture, beat) { mutableIntStateOf(intent.getIntExtra("selection", 0)) }
                var catch by remember { mutableStateOf(true) }
                var chosenPair by remember { mutableStateOf<LanguagePair?>(null) }
                var fixtureMenu by remember { mutableStateOf(false) }
                var beatMenu by remember { mutableStateOf(false) }
                var wholeMoved by remember(fixture, beat) { mutableStateOf(false) }
                val largeText = LocalDensity.current.fontScale > 1.3f
                val state = catalog.state(fixture, beat).let { initial ->
                    val base = if (beat == 14 && (wholeMoved || intent.getBooleanExtra("whole", false))) {
                        val merged = catalog.state(fixture, 13).wearer!!
                        initial.copy(heard = merged.copy(speaker = Speaker.THEM), wearer = null,
                            correction = CorrectionReceipt(1, merged.id, merged.text, merged.lang, Speaker.THEM, merged.roman))
                    } else initial
                    base.copy(displayPaused = displayPaused, selectedReply = if (base.replies.isEmpty()) null else selected.coerceAtMost(base.replies.lastIndex), pendingPair = chosenPair ?: base.pendingPair)
                }
                CompanionScreen(
                    state, configuredPair = chosenPair ?: state.pendingPair ?: state.pair, catchForgottenTaps = catch,
                    initialTab = when (intent.getStringExtra("screen")) { "today" -> CompanionTab.TODAY; "voice" -> CompanionTab.VOICE; else -> CompanionTab.LIVE },
                    initialSheet = intent.getStringExtra("sheet"),
                    onAction = { action -> when (action) {
                        HudAction.Talk -> beat = 3
                        HudAction.Done -> beat = if (beat == 3) 16 else 8
                        HudAction.ToggleDisplay -> displayPaused = !displayPaused
                        HudAction.Next -> selected = (selected + 1).coerceAtMost(state.replies.lastIndex.coerceAtLeast(0))
                        HudAction.Prev -> selected = (selected - 1).coerceAtLeast(0)
                        is HudAction.Repair -> if (action.whole && beat == 14) wholeMoved = true else beat = if (beat == 13) 14 else 18
                        is HudAction.Undo -> beat = 15
                        else -> Unit
                    } },
                    onListen = { beat = 0 }, onStopListening = { beat = 16 }, onPair = { chosenPair = it },
                    onCatch = { catch = it }, onCoached = {}, onBuildVoice = { "Synthetic walkthrough: no profile is built or saved." },
                    debugControls = {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Text(if (largeText) "Synthetic walkthrough" else "Synthetic · ${catalog.fixtureLabel(fixture)}", style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        TextButton(onClick = { beatMenu = true }, modifier = Modifier.semantics { contentDescription = "Choose beat: ${catalog.beatLabel(beat)}" }) { Text("${beat + 1}/20 · ${if (largeText) "Beat" else catalog.beatLabel(beat)}", style = MaterialTheme.typography.labelMedium) }
                                        DropdownMenu(expanded = beatMenu, onDismissRequest = { beatMenu = false }) {
                                            repeat(20) { i -> DropdownMenuItem(text = { Text(catalog.beatLabel(i)) }, onClick = { beat = i; beatMenu = false }) }
                                        }
                                    }
                                    Box {
                                        TextButton(onClick = { fixtureMenu = true }) { Text("Scene") }
                                        DropdownMenu(expanded = fixtureMenu, onDismissRequest = { fixtureMenu = false }) {
                                            repeat(4) { i -> DropdownMenuItem(text = { Text(catalog.fixtureLabel(i)) }, onClick = { fixture = i; chosenPair = null; fixtureMenu = false }) }
                                        }
                                    }
                                    TextButton(onClick = { beat = (beat + 1) % 20 }) { Text(if (largeText) "Next" else "Next beat") }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

class CompanionFixtures(context: Context) {
    private val data = JSONObject(context.assets.open("companion/study07.json").bufferedReader().use { it.readText() })
    fun fixtureLabel(fixture: Int): String = data.getJSONArray("fixtures").getJSONObject(fixture).getString("label")
    fun beatLabel(beat: Int): String = data.getJSONArray("beats").getJSONArray(beat).getString(1)

    fun state(fixture: Int, beat: Int): HudState {
        val f = data.getJSONArray("fixtures").getJSONObject(fixture)
        val pair = LanguagePair(Language.byCode(f.getString("me")), Language.byCode(f.getString("them")))
        val script = if (pair.them.base != "zh") "es" else if (pair.them.traditional) "hant" else "hans"
        fun words(key: String, lang: String = script) = data.getJSONObject("lines").getJSONObject(key).getJSONObject(lang)
        fun line(key: String, id: Long, speaker: Speaker) = words(key).let { l -> Line(l.getString("text"), pair.them.code, words(key, "en").getString("text"), id, speaker, l.optString("roman").ifBlank { null }) }
        fun reply(key: String) = words(key).let { l -> Reply(l.getString("text"), l.getString("text"), l.optString("roman").ifBlank { null }, words(key, "en").getString("text")) }
        val capture = CaptureState(
            phase = when (beat) { 3 -> CapturePhase.ARMED; 4, 5, 6 -> CapturePhase.SPEAKING; 7 -> CapturePhase.CLOSING; 8 -> CapturePhase.FINISHING; else -> CapturePhase.LISTENING },
            turnId = 2, speaker = if (beat in 3..8) Speaker.ME else Speaker.THEM,
            ready = true, closedTurnId = if (beat == 9) 2 else null,
        )
        var s = HudState(phase = Phase.SHOWING, pair = pair, listening = true, modelReady = true, synthetic = true,
            capture = capture, heard = if (beat > 0) line("question", 1, Speaker.THEM) else null,
            replies = if (beat >= 2 && beat !in setOf(10, 11, 13, 15)) listOf(reply("reply0"), reply("reply1"), reply("reply2")) else emptyList(),
            selectedReply = if (beat >= 2 && beat !in setOf(10, 11, 13, 15)) 0 else null,
            processing = beat == 1 || beat == 9,
            statusLine = when (beat) { 16 -> "No speech after that tap. Still listening for them."; 17 -> "Caught your forgotten tap · extra language detection"; 18 -> "Catch is off · your untapped line was treated as theirs. Correct the line to repair it."; else -> null },
        )
        if (beat == 5) s = s.copy(hint = HintState(HintPhase.PENDING, pauseId = 1))
        if (beat == 6) s = s.copy(hint = HintState(HintPhase.READY, words("hint").getString("text"), words("hint").optString("roman").ifBlank { null }, "oats", 1))
        if (beat == 10) s = s.copy(wearer = line("reply0", 2, Speaker.ME), matched = true, learnedToday = 1)
        if (beat == 11) s = s.copy(wearer = line(if (fixture == 3) "takeaway" else "original", 2, Speaker.ME))
        if (beat == 12 || beat == 17) s = s.copy(aside = Line(words("help", "en").getString("text"), pair.me.code, id = 2, speaker = Speaker.ME), replies = listOf(reply("help"), reply("variant1")), replyKind = ReplyKind.VARIANTS)
        if (beat == 13 || beat == 15) s = s.copy(wearer = line("original", 2, Speaker.ME).let { it.copy(text = it.text + " " + words("tail").getString("text")) })
        if (beat == 14) s = s.copy(heard = line("tail", 3, Speaker.THEM), wearer = line("original", 2, Speaker.ME), replies = listOf(reply("repairedReply")), correction = CorrectionReceipt(1, 2, words("tail").getString("text"), pair.them.code, Speaker.THEM, words("tail").optString("roman").ifBlank { null }, canMoveWhole = true))
        if (beat == 18) s = s.copy(heard = line("help", 2, Speaker.THEM), replies = listOf(reply("badReply")))
        if (beat == 19) s = s.copy(pendingPair = LanguagePair(pair.me, if (pair.them.base == "zh") Language.ES else Language.ZH_TW))
        return s.copy(lastWearerTurnId = if (beat in 9..15) 2 else null, sessionId = "synthetic-walkthrough")
    }
}

package dev.dejalango.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.LanguagePair
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.hud.*
import dev.dejalango.engine.voice.Confidence
import dev.dejalango.engine.voice.VoiceCard
import kotlinx.coroutines.launch

internal enum class CompanionTab(val label: String, val mark: Mark) {
    TODAY("Today", Mark.HOME), LIVE("Live", Mark.CHAT), VOICE("Your voice", Mark.BOOK)
}

/** Presentation consumes shared runtime state; changing destination never starts, clears, or stops a turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionScreen(
    state: HudState,
    configuredPair: LanguagePair = state.pendingPair ?: state.pair,
    ready: Boolean = true,
    starting: Boolean = false,
    error: String? = null,
    catchForgottenTaps: Boolean = true,
    coachingSeen: Boolean = true,
    card: VoiceCard? = state.voiceCard,
    initialTab: CompanionTab = CompanionTab.TODAY,
    initialSheet: String? = null,
    onAction: (HudAction) -> Unit,
    onListen: () -> Unit,
    onStopListening: () -> Unit,
    onPair: (LanguagePair) -> Unit,
    onCatch: (Boolean) -> Unit,
    onCoached: () -> Unit,
    onBuildVoice: suspend (List<String>) -> String,
    onRetrySettings: () -> Unit = {},
    onDeveloper: (() -> Unit)? = null,
    debugControls: (@Composable () -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val navigationProtection = WindowInsets.tappableElement.getBottom(LocalDensity.current)
    val navigationPadding = with(LocalDensity.current) { navigationProtection.toDp() }
    val sendAction: (HudAction) -> Unit = { action ->
        view.performHapticFeedback(if (action == HudAction.Done) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
        onAction(action)
    }
    fun beginListening() { if (!coachingSeen) sheet = "coach" else onListen() }
    BackHandler(enabled = sheet == null && tab != CompanionTab.TODAY) { tab = CompanionTab.TODAY }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface).protectButtonNavigation().safeDrawingPadding()) {
        val expanded = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded) NavigationRail(containerColor = colors.surface, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(24.dp))
                CompanionTab.entries.forEach { destination ->
                    NavigationRailItem(selected = tab == destination, onClick = { tab = destination },
                        icon = { MarkIcon(destination.mark) }, label = { Text(destination.label) })
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                Scaffold(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(), containerColor = colors.surface,
                    contentWindowInsets = WindowInsets(0),
                    topBar = {
                        Column {
                            TopAppBar(title = { Text("DejaLango.", style = MaterialTheme.typography.headlineMedium, color = colors.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                actions = {
                                    IconButton(onClick = { if (state.listening) onStopListening() else beginListening() }, enabled = ready && !starting,
                                        modifier = Modifier.semantics { contentDescription = if (state.listening) "Pause listening" else "Start listening" }) {
                                        MarkIcon(if (state.listening) Mark.PAUSE else Mark.MIC)
                                    }
                                    IconButton(onClick = { sheet = "settings" }, modifier = Modifier.semantics { contentDescription = "Settings" }) { MarkIcon(Mark.SETTINGS) }
                                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface))
                            PairSummary(configuredPair, state.pendingPair != null, onOpen = { sheet = "pair" },
                                onSwap = { onPair(configuredPair.swapped()) })
                            debugControls?.invoke()
                        }
                    },
                    bottomBar = {
                        Column {
                            GlassesController(state, enabled = ready && !starting, onAction = sendAction)
                            if (!expanded) NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                                CompanionTab.entries.forEach { destination ->
                                    NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                                        icon = { MarkIcon(destination.mark) }, label = { Text(destination.label) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = colors.primaryContainer, selectedIconColor = colors.primary, selectedTextColor = colors.primary,
                                            unselectedIconColor = colors.onSurfaceVariant, unselectedTextColor = colors.onSurfaceVariant))
                                }
                            }
                        }
                    },
                ) { insets ->
                    Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!ready) {
                            StatusMessage(error ?: "Loading your settings…", error = error != null)
                            if (error != null) Button(onClick = onRetrySettings) { Text("Retry settings") }
                        } else {
                            if (state.synthetic && debugControls == null) StatusMessage("Synthetic model · developer test. These words are canned, not a transcript of your conversation.")
                            (error ?: state.error)?.let { StatusMessage(it, error = true) }
                            if (starting || state.phase == Phase.WARMING) StatusMessage("Getting your language model ready…")
                            when (tab) {
                                CompanionTab.TODAY -> TodayContent(configuredPair, state, onLive = { tab = CompanionTab.LIVE }, onStart = ::beginListening,
                                    onPractice = if (debugControls != null) ({ sheet = "practice" }) else null)
                                CompanionTab.LIVE -> LiveContent(state, onAction, onPartner = { sheet = "partner" },
                                    onStart = ::beginListening, starting = starting)
                                CompanionTab.VOICE -> VoiceContent(configuredPair, card, state.modelReady, onBuildVoice)
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.surfaceContainer,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !isSystemInDarkTheme(),
            isAppearanceLightNavigationBars = !isSystemInDarkTheme() && WindowInsets.tappableElement.getBottom(LocalDensity.current) == 0),
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.widthIn(min = 48.dp)) }) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().protectButtonNavigation(navigationProtection).padding(bottom = navigationPadding)) {
        when (sheet) {
            "pair" -> PairSheet(configuredPair, onSave = { onPair(it); sheet = null })
            "settings" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("A little more control", style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Catch forgotten taps", style = MaterialTheme.typography.titleSmall)
                        Text("If you forget Talk and speak ${configuredPair.me.name}, try to catch it. Each untapped turn needs extra language detection and may take longer.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    Switch(checked = catchForgottenTaps, onCheckedChange = onCatch, modifier = Modifier.semantics { contentDescription = "Catch forgotten taps" })
                }
                Text("With this off, untapped speech is treated as theirs. You can still correct a line.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { sheet = "coach" }) { Text("How Talk and Done work") }
                Text("Audio stays in memory on this phone. The language model runs locally.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                onDeveloper?.let { TextButton(onClick = { sheet = null; it() }) { Text("Developer tools") } }
            }
            "coach" -> Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Buddy(0, Modifier.size(112.dp).align(Alignment.CenterHorizontally))
                    Text("Your turn. One tap.", style = MaterialTheme.typography.headlineMedium)
                    Text("Tap “I’m talking” just before you speak. Everything else is treated as their turn.")
                    Text("Done closes your turn sooner. Forget it? A short silence closes it for you. If you get stuck, tap Talk again and say it in ${configuredPair.me.name}.")
                    Text("Your words appear after the turn. A tick marks the tap; a second tick marks the capture closing.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { onCoached(); sheet = null; onListen() }, modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth().heightIn(min = 52.dp)) { Text("Got it · start listening") }
            }
            "partner" -> {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
                    PartnerSheet(state, Modifier.weight(1f))
                    GlassesController(state, enabled = ready && !starting, onAction = sendAction)
                    TextButton(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back to Live") }
                }
            }
            "practice" -> FuturePractice(configuredPair)
        }
        }
    }
}

@Composable
private fun PairSummary(pair: LanguagePair, pending: Boolean, onOpen: () -> Unit, onSwap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Column(Modifier.fillMaxWidth()) { Text("I speak", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant); Text(pair.me.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface) }
            }
            IconButton(onClick = onSwap, modifier = Modifier.semantics { contentDescription = "Swap languages" }) { MarkIcon(Mark.SWAP) }
            TextButton(onClick = onOpen, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.weight(1.25f).heightIn(min = 52.dp)) {
                Column(Modifier.fillMaxWidth()) { Text("They speak", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant); Text(pair.them.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface) }
            }
        }
        if (pending) Text("Pair saved · applies to the next turn", color = colors.primary, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun TodayContent(pair: LanguagePair, state: HudState, onLive: () -> Unit, onStart: () -> Unit, onPractice: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hey, you.\nLet’s talk.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("A little ${pair.them.name}.\nA lot like you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Buddy(0, Modifier.size(140.dp))
    }
    Button(onClick = onLive, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        MarkIcon(Mark.CHAT, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("Open Live")
    }
    if (!state.listening) {
        Text("Ready when you are", style = MaterialTheme.typography.titleLarge)
        Text("Start listening when you’re with someone. Tap before you speak; your companion will help with what comes next.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onStart, enabled = state.phase != Phase.WARMING, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start listening") }
    } else Text("Live is listening. Your turn stays with you when you move around the app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    onPractice?.let {
        OutlinedButton(onClick = it, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Column(Modifier.weight(1f)) { Text("A café, a tiny adventure.", style = MaterialTheme.typography.titleLarge); Text("Future practice concept", style = MaterialTheme.typography.bodyMedium) }
            MarkIcon(Mark.NEXT)
        }
    }
}

internal fun turnTitle(s: HudState): String = when {
    s.capture.phase == CapturePhase.ARMED -> "Go ahead. I’m ready."
    s.capture.myTurn && s.capture.phase == CapturePhase.FINISHING -> "Finishing your last word…"
    s.capture.myTurn && s.capture.phase == CapturePhase.CLOSING -> "Closing your turn…"
    s.capture.myTurn -> "You’re talking"
    s.capture.closedTurnId != null -> "Sent · listening for them"
    s.replyKind == ReplyKind.VARIANTS && s.replies.isNotEmpty() -> "Say it in ${s.pair.them.name}"
    s.replies.isNotEmpty() -> "Your kind of reply"
    s.processing -> "A little help is on its way"
    s.wearer != null -> if (s.matched) "Reply counted" else "Your words"
    s.listening -> "Listening for them"
    else -> "Let’s hear them"
}

@Composable
private fun LiveContent(s: HudState, onAction: (HudAction) -> Unit, onPartner: () -> Unit, onStart: () -> Unit, starting: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(turnTitle(s), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("live-title").semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(when {
                s.capture.phase == CapturePhase.ARMED -> "Speak when you’re ready. A stray tap clears itself."
                s.capture.myTurn && s.capture.phase == CapturePhase.CLOSING -> "Done is optional. Silence closes the turn too."
                s.capture.myTurn && s.capture.phase == CapturePhase.FINISHING -> "Keep going if you’re finishing a word."
                s.capture.myTurn -> "Done when you finish. Stuck? Tap Talk again and use ${s.pair.me.name}."
                s.capture.closedTurnId != null -> "Your audio is captured. Recognition follows."
                s.replies.isNotEmpty() -> "Read a reply aloud after a Talk tap."
                s.listening -> "Tap just before you speak."
                else -> "Start listening to join a conversation."
            }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Buddy(if (s.matched) 3 else if (s.capture.myTurn) 2 else 1, Modifier.padding(start = 8.dp).size(74.dp))
    }
    s.correction?.let { receipt ->
        Surface(Modifier.fillMaxWidth(), color = colors.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (receipt.pending) "Checking the line…" else if (receipt.canMoveWhole && receipt.text.isBlank()) "Choose what to move" else "Moved to ${if (receipt.to == Speaker.THEM) "them" else "you"}", style = MaterialTheme.typography.titleSmall)
                if (receipt.text.isNotBlank()) Text("“${receipt.text}”")
                receipt.roman?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 16.sp) }
                receipt.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onAction(HudAction.Undo(receipt.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
                    if (receipt.canMoveWhole) TextButton(onClick = { onAction(HudAction.Repair(receipt.lineId, whole = true)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Move whole line") }
                }
            }
        }
    }
    if (s.hint.phase != HintPhase.NONE) HintBand(s.hint)
    s.heard?.let { line -> ConversationLine("They said", line, canRepair = !s.capture.myTurn && !s.processing, onFlip = { onAction(HudAction.Repair(line.id)) }) }
    if (s.capture.myTurn && s.capture.phase != CapturePhase.ARMED && s.hint.phase == HintPhase.NONE) {
        Surface(color = colors.primaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Capturing your turn", style = MaterialTheme.typography.titleSmall)
                Text("Your line appears after this turn.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    s.aside?.let { ConversationLine("You · ${s.pair.me.name}", it, !s.capture.myTurn && !s.processing) { onAction(HudAction.Repair(it.id)) } }
    s.wearer?.let { line ->
        ConversationLine("You", line, !s.capture.myTurn && !s.processing) { onAction(HudAction.Repair(line.id)) }
        if (s.matched) Text("Reply counted from your speech.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (s.correction?.let { it.lineId == line.id && it.to == Speaker.THEM && it.text.isNotBlank() && !it.pending } == true) {
            Text("Split here · the last part now belongs to them.", style = MaterialTheme.typography.bodySmall, color = colors.primary)
        }
    }
    val index = s.selectedReply ?: 0
    s.replies.getOrNull(index)?.let { reply ->
        Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("${if (s.replyKind == ReplyKind.VARIANTS) "Say it" else "Reply"} in ${s.pair.them.name}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("${index + 1} / ${s.replies.size}", style = MaterialTheme.typography.labelMedium)
                }
                Text(reply.yours, style = MaterialTheme.typography.headlineMedium)
                if (s.pair.them.romanization != null) reply.roman?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 16.sp, lineHeight = 23.sp) }
                reply.back?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.voiceCard?.confidence?.let { it != Confidence.NONE } == true) "Your voice" else "A little help", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onAction(HudAction.Prev) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = "Previous reply" },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSecondaryContainer, disabledContentColor = colors.onSecondaryContainer.copy(alpha = .38f))) { MarkIcon(Mark.BACK) }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onAction(HudAction.Next) }, enabled = index < s.replies.lastIndex, modifier = Modifier.semantics { contentDescription = "Next reply" },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSecondaryContainer, disabledContentColor = colors.onSecondaryContainer.copy(alpha = .38f))) { MarkIcon(Mark.NEXT) }
                }
            }
        }
    }
    if (!s.listening && !starting) Button(onClick = onStart, enabled = s.phase != Phase.WARMING, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start listening") }
    if (s.listening) TextButton(onClick = onPartner, modifier = Modifier.heightIn(min = 48.dp)) {
        MarkIcon(Mark.PERSON); Spacer(Modifier.width(8.dp)); Text("Show a turn cue to them")
    }
    if (s.replies.isEmpty() && s.listening && !s.processing && !s.capture.myTurn && s.heard == null) {
        Text("Their words, your next thought. We’ll make room for both.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
    s.statusLine?.takeIf { it.isNotBlank() && s.error == null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
}

@Composable
private fun ConversationLine(label: String, line: Line, canRepair: Boolean, onFlip: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onFlip, enabled = canRepair, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp), modifier = Modifier.widthIn(min = 60.dp, max = 84.dp).heightIn(min = 48.dp).semantics {
                    contentDescription = if (line.speaker == Speaker.THEM) "That was me: ${line.text}" else "That was them: ${line.text}"
                }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    MarkIcon(Mark.SWAP, Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.text, style = MaterialTheme.typography.titleMedium)
                line.roman?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                line.translation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun HintBand(hint: HintState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("A word, if you need it", style = MaterialTheme.typography.labelMedium)
            Text(if (hint.phase == HintPhase.PENDING) "…" else hint.word, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            hint.roman?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 16.sp) }
            hint.meaning?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun GlassesController(s: HudState, enabled: Boolean, onAction: (HudAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkIcon(Mark.GLASSES, Modifier.padding(horizontal = 4.dp), color = colors.primary)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Your glasses", style = MaterialTheme.typography.labelMedium)
                    Text(if (s.displayPaused) "Display paused · listening continues" else if (!s.listening) "Ready when you are" else turnTitle(s),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { onAction(HudAction.ToggleDisplay) }, modifier = Modifier.semantics {
                    contentDescription = if (s.displayPaused) "Resume glasses display" else "Pause glasses display"
                }) { MarkIcon(if (s.displayPaused) Mark.PLAY else Mark.PAUSE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftPressButton("I’m talking", Mark.MIC, Modifier.weight(1.4f),
                    enabled = enabled && s.listening && s.modelReady && s.capture.ready,
                    active = !s.capture.myTurn, onClick = { onAction(HudAction.Talk) })
                SoftPressButton("Done", Mark.CHECK, Modifier.weight(1f),
                    enabled = enabled && s.listening && s.capture.myTurn && s.capture.phase != CapturePhase.FINISHING,
                    active = true, onClick = { onAction(HudAction.Done) })
            }
        }
    }
}

@Composable
internal fun SoftPressButton(label: String, mark: Mark, modifier: Modifier = Modifier, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val motion = motionAllowed()
    val travel by animateDpAsState(if (pressed && motion && touch) 4.dp else 0.dp,
        tween(if (motion && touch) 110 else 0, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "press travel")
    val face = if (active && enabled) colors.primary else colors.primaryContainer
    val ink = if (active && enabled) colors.onPrimary else if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.background(if (active && enabled) colors.tertiary else colors.outlineVariant, shape).padding(bottom = 5.dp)) {
        Button(onClick = onClick, enabled = enabled, interactionSource = interaction, shape = shape,
            modifier = Modifier.fillMaxWidth().offset { IntOffset(0, travel.roundToPx()) }.heightIn(min = 60.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = face, contentColor = ink, disabledContainerColor = colors.primaryContainer, disabledContentColor = colors.onSurfaceVariant),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)) {
            MarkIcon(mark, Modifier.size(18.dp), color = ink); Spacer(Modifier.width(7.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun motionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var allowed by remember { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { allowed = enabled() } }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return allowed
}

@Composable
private fun StatusMessage(text: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (error) colors.errorContainer else colors.primaryContainer, contentColor = if (error) colors.onErrorContainer else colors.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairSheet(pair: LanguagePair, onSave: (LanguagePair) -> Unit) {
    var me by remember(pair) { mutableStateOf(pair.me) }
    var them by remember(pair) { mutableStateOf(pair.them) }
    var editingMe by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Two languages. Your way.", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = editingMe, onClick = { editingMe = true }, label = { Text("I speak") }, modifier = Modifier.testTag("pair-me").heightIn(min = 48.dp))
            FilterChip(selected = !editingMe, onClick = { editingMe = false }, label = { Text("They speak") }, modifier = Modifier.testTag("pair-them").heightIn(min = 48.dp))
            IconButton(onClick = { val previous = me; me = them; them = previous }, modifier = Modifier.semantics { contentDescription = "Swap draft languages" }) { MarkIcon(Mark.SWAP) }
        }
        Text("${me.label}  ⇄  ${them.label}", style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Language.VARIETIES.groupBy { it.base }.values.forEach { group ->
                Text(group.first().name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                group.forEach { language ->
                    val selected = language == if (editingMe) me else them
                    val enabled = language.base != if (editingMe) them.base else me.base
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.RadioButton) { if (editingMe) me = language else them = language }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(language.label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            language.scriptLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (!LanguagePair.isValid(me, them)) Text("Choose two different languages.", color = MaterialTheme.colorScheme.error)
        Text("Changes apply to the next turn. We keep the current turn in its original pair.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onSave(LanguagePair(me, them)) }, enabled = LanguagePair.isValid(me, them), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save language pair") }
    }
}

internal fun partnerMayAnswer(s: HudState): Boolean {
    val wearer = s.wearer ?: return false
    return s.listening && !s.capture.myTurn && !s.processing && wearer.id == s.lastWearerTurnId &&
        Language.byCode(wearer.lang).base == s.pair.them.base && s.error == null
}

@Composable
private fun PartnerSheet(s: HudState, modifier: Modifier = Modifier) {
    val go = partnerMayAnswer(s)
    val chinese = s.pair.them.base == "zh"
    val spanish = s.pair.them.base == "es"
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("For the person with you", style = MaterialTheme.typography.titleSmall)
        Text(if (go) { if (chinese) "請說吧。".let { if (s.pair.them.traditional) it else "请说吧。" } else if (spanish) "Tu turno." else "Your turn." }
            else { if (chinese) if (s.pair.them.traditional) "請稍等一下。" else "请稍等一下。" else if (spanish) "Un momento, por favor." else "One moment, please." }, style = MaterialTheme.typography.displaySmall)
        if (chinese) Text(if (go) "Qǐng shuō ba." else "Qǐng shāo děng yíxià.", fontSize = 16.sp)
        Text(if (go) "Your turn." else "One moment, please.", style = MaterialTheme.typography.titleLarge)
        Text(if (go) "Your turn has been recognized in ${s.pair.them.name}." else "The cue waits until your turn has been recognized in ${s.pair.them.name}. Done only closes the capture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.aside != null && !go) Text("Read a translated variant aloud after tapping Talk. Then the cue can invite them to answer.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VoiceContent(pair: LanguagePair, card: VoiceCard?, modelReady: Boolean, onBuild: suspend (List<String>) -> String) {
    var text by rememberSaveable(pair.me.code) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text("A little more like you", style = MaterialTheme.typography.headlineMedium)
    Text("How you speak in ${pair.me.name} can shape replies in ${pair.them.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (card == null || card.confidence == Confidence.NONE) {
        Buddy(1, Modifier.size(120.dp).fillMaxWidth())
        Text("No voice profile yet", style = MaterialTheme.typography.titleLarge)
        Text("Add words you’ve written or spoken yourself. Reading a suggested reply never teaches the app your style.")
    } else {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (card.confidence == Confidence.QUICK) "A first impression · this session" else "Your voice profile", style = MaterialTheme.typography.titleLarge)
                Text(card.describe())
            }
        }
    }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your own sentences") },
        placeholder = { Text("One sentence per line") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).semantics { contentDescription = "Your own sentences in ${pair.me.name}" },
        enabled = !busy, minLines = 4, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    Text("Use only your own words. A longer sample gives the model more to work with. Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = {
        busy = true; result = null
        scope.launch { try { result = onBuild(text.lines().map(String::trim).filter(String::isNotEmpty)) } finally { busy = false } }
    }, enabled = modelReady && !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Finding your voice…" else "Build my voice profile") }
    if (!modelReady) Text("Start listening to load the local model before building your profile.", style = MaterialTheme.typography.bodyMedium)
    result?.let { StatusMessage(it) }
}

@Composable
private fun FuturePractice(pair: LanguagePair) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("A café, a tiny adventure.", style = MaterialTheme.typography.headlineMedium)
        Text("Future practice concept · not available yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Image(companionBitmap("practice_cafe.webp"), contentDescription = "An imaginary café for a future practice scene", modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Text("An imaginary barista asks what you’d like. Your tiny goal: ask for oat milk.")
        Buddy(2, Modifier.size(100.dp).align(Alignment.End))
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start in ${pair.me.name}") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Try ${pair.them.name} first") }
        Text("Choose either approach for each future scenario. Personal context and trip adventures are future ideas; this is a visual preview.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

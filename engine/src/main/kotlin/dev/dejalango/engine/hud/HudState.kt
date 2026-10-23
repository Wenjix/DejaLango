package dev.dejalango.engine.hud

import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.llm.ModelInfo
import dev.dejalango.engine.trace.TurnTrace
import dev.dejalango.engine.voice.VoiceCard

enum class Phase { WARMING, IDLE, LISTENING, THINKING, SHOWING, ERROR }

/** Something somebody said: the text as spoken, its language, and the L1 translation. */
data class Line(val text: String, val lang: String, val translation: String? = null)

/**
 * One suggested thing for ME to say. [plain] is the phrasebook version, [yours] the personalized one.
 * Glasses show [yours] (+ [roman] for non-Latin scripts); the bench shows both and the back-translation.
 */
data class Reply(
    val plain: String,
    val yours: String,
    val roman: String? = null,
    val back: String? = null,
    val usedExemplarIds: List<Long> = emptyList(),
)

/** Exemplar as shown in the bench's glass box (no vectors). */
data class ExemplarView(val id: Long, val text: String, val score: Float)

/**
 * The single source of truth for every renderer. Immutable; replaced wholesale on each change.
 * Fields marked (bench) are ignored by glasses renderers.
 */
data class HudState(
    val phase: Phase = Phase.WARMING,
    val mode: Mode = Mode.REPLY_OPTIONS,
    val level: Float = 0f,                       // VAD probability, for the meter
    val heard: Line? = null,
    val replies: List<Reply> = emptyList(),
    val selectedReply: Int? = null,              // highlighted by D-pad / tap
    val showPlain: Boolean = false,              // Phrasebook ⇄ You toggle
    val wordHint: String? = null,                // word-finding mode
    val wordAlternatives: List<String> = emptyList(),
    val exemplarsUsed: List<ExemplarView> = emptyList(), // (bench)
    val voiceCard: VoiceCard? = null,            // (bench)
    val trace: TurnTrace? = null,                // (bench)
    val learnedToday: Int = 0,
    val model: ModelInfo? = null,                // (bench)
    val statusLine: String? = null,              // short human-readable status, e.g. "loading model 2/2"
    val error: String? = null,
) {
    /** What a 600×600 additive display should show: at most two lines of context and two replies. */
    fun forGlasses(): HudState = copy(exemplarsUsed = emptyList(), voiceCard = null, trace = null, model = null)
}

/** Input coming back from a renderer: D-pad on glasses, taps on the bench, Neural Band one day. */
sealed interface HudAction {
    data class Select(val index: Int) : HudAction
    object Next : HudAction
    object Prev : HudAction
    object Confirm : HudAction
    object TogglePlain : HudAction
    object Clear : HudAction
    data class SetMode(val mode: Mode) : HudAction
    object CutNow : HudAction
}

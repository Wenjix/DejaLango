package dev.dejalango.engine.hud

import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.config.LanguagePair
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.llm.ModelInfo
import dev.dejalango.engine.trace.TurnTrace
import dev.dejalango.engine.voice.VoiceCard

enum class Phase { WARMING, IDLE, LISTENING, THINKING, SHOWING, ERROR }

/** Something somebody said: the text as spoken, its language, and the L1 translation. */
data class Line(
    val text: String,
    val lang: String,
    val translation: String? = null,
    val id: Long = 0,
    val speaker: Speaker = Speaker.UNKNOWN,
    val roman: String? = null,
)

enum class CapturePhase { STOPPED, LISTENING, ARMED, SPEAKING, CLOSING, FINISHING }
data class CaptureState(
    val phase: CapturePhase = CapturePhase.STOPPED,
    val turnId: Long = 0,
    val speaker: Speaker = Speaker.THEM,
    val deadlineMs: Long? = null,
    val frameTimeMs: Long = 0,
    val ready: Boolean = false,
    val closedTurnId: Long? = null,
) {
    val myTurn: Boolean get() = phase == CapturePhase.ARMED || speaker == Speaker.ME && phase in setOf(CapturePhase.SPEAKING, CapturePhase.CLOSING, CapturePhase.FINISHING)
}
enum class HintPhase { NONE, PENDING, READY }
data class HintState(val phase: HintPhase = HintPhase.NONE, val word: String = "", val roman: String? = null, val meaning: String? = null, val pauseId: Long = 0)
enum class ReplyKind { REPLIES, VARIANTS }
data class CorrectionReceipt(
    val id: Long,
    val lineId: Long,
    val text: String,
    val lang: String,
    val to: Speaker,
    val roman: String? = null,
    val pending: Boolean = false,
    val canMoveWhole: Boolean = false,
    val message: String? = null,
)

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
    val capture: CaptureState = CaptureState(),
    val wearer: Line? = null,
    val aside: Line? = null,
    val pair: LanguagePair = LanguagePair(Language.EN, Language.ZH),
    val pendingPair: LanguagePair? = null,
    val pairRevision: Long = 0,
    val offerId: Long = 0,
    val replyKind: ReplyKind = ReplyKind.REPLIES,
    val hint: HintState = HintState(),
    val correction: CorrectionReceipt? = null,
    val displayPaused: Boolean = false,
    val listening: Boolean = false,
    val modelReady: Boolean = false,
    val processing: Boolean = false,
    val matched: Boolean = false,
    val lastWearerTurnId: Long? = null,
    val synthetic: Boolean = false,
    val sessionId: String = "",
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
    object Talk : HudAction
    object Done : HudAction
    object ToggleDisplay : HudAction
    data class Repair(val lineId: Long, val whole: Boolean = false) : HudAction
    data class Undo(val correctionId: Long) : HudAction
}

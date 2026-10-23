package dev.dejalango.engine.turn

import dev.dejalango.engine.config.Mode

/** How a turn is cut out of the stream once the segmenter decides one has ended. */
sealed interface WindowMode {
    /** The whole utterance from onset (minus pre-roll) to the end of speech. */
    object FullTurn : WindowMode

    /** The trailing [windowMs] before the pause — word-finding mode wants "what I just said". */
    data class Trailing(val windowMs: Long) : WindowMode
}

/**
 * Trigger policy with explicit parameters:
 * trigger once per pause (edge-triggered state machine), cancel if speech resumes
 * ([resumeGraceMs]), minimum spacing between suggestions ([cooldownMs]), confidence gate
 * (applied by the processor, see TurnProcessor).
 */
data class TurnPolicy(
    val onThreshold: Float = 0.5f,
    val offThreshold: Float = 0.35f,
    val minSpeechMs: Long = 600,
    val endSilenceMs: Long = 700,
    val resumeGraceMs: Long = 300,
    val resumeConfirmMs: Long = 250,
    val preRollMs: Long = 300,
    val tailMs: Long = 100,
    val maxTurnMs: Long = 28_000,
    val cooldownMs: Long = 0,
    val window: WindowMode = WindowMode.FullTurn,
) {
    init {
        require(offThreshold <= onThreshold) { "offThreshold must be ≤ onThreshold (hysteresis)" }
        require(maxTurnMs in 1_000..30_000) { "maxTurnMs must stay under Gemma's 30 s audio cap" }
    }

    companion object {
        val REPLY_OPTIONS = TurnPolicy()
        val SAY_IT_MY_WAY = TurnPolicy()
        val WORD_FINDING = TurnPolicy(
            minSpeechMs = 1_500,
            endSilenceMs = 500,
            resumeGraceMs = 0,
            preRollMs = 0,
            cooldownMs = 2_000,
            window = WindowMode.Trailing(windowMs = 8_000),
        )

        fun forMode(mode: Mode): TurnPolicy = when (mode) {
            Mode.REPLY_OPTIONS -> REPLY_OPTIONS
            Mode.SAY_IT_MY_WAY -> SAY_IT_MY_WAY
            Mode.WORD_FINDING -> WORD_FINDING
        }
    }
}

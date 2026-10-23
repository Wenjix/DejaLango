package dev.dejalango.engine.turn

import dev.dejalango.engine.audio.AudioTurn

enum class EndReason {
    /** Normal end: silence after speech. */
    SILENCE,

    /** Speech ran into the 30 s cap; a new turn starts immediately. */
    MAX_LENGTH,

    /** Trailing window emitted at a mid-sentence pause (word-finding mode). */
    PAUSE,

    /** The user or the bench forced a cut. */
    MANUAL,
}

sealed interface TurnEvent {
    /** Speech probability for the current frame — for the UI meter. Emitted every frame; conflate it. */
    data class Level(val prob: Float, val tMs: Long) : TurnEvent

    /** Speech has persisted past minSpeechMs: a turn is in progress. */
    data class SpeechStarted(val tMs: Long) : TurnEvent

    /** A turn is complete and ready for the model. */
    data class TurnEnded(val turn: AudioTurn, val reason: EndReason) : TurnEvent

    /** Speech resumed within the grace window after a TurnEnded: the previous turn was probably not over. */
    data class SpeechResumed(val tMs: Long, val afterTurnEndedAtMs: Long) : TurnEvent
}

package dev.dejalango.engine.trace

/**
 * Stage timestamps for one turn, in epoch ms. Rendered as bars on the bench and appended to a CSV.
 * Mutable while the turn is in flight; renderers get copies.
 */
data class TurnTrace(
    val turnId: Long,
    val tTurnEnd: Long,
    val audioMs: Long,
    val reason: String,
    var tStageAStart: Long? = null,
    var tAFirstToken: Long? = null,
    var tAEnd: Long? = null,
    var tRetrieved: Long? = null,
    var tBStart: Long? = null,
    var tBFirstToken: Long? = null,
    var tBEnd: Long? = null,
    var tRendered: Long? = null,
    var tokensA: Int = 0,
    var tokensB: Int = 0,
    var decodeTpsA: Double? = null,
    var decodeTpsB: Double? = null,
    var backend: String = "",
    var shape: String = "",
    var cancelled: Boolean = false,
    var timedOut: Boolean = false,
    var parseFailed: Boolean = false,
    var repaired: Boolean = false,
) {
    fun transcriptLatencyMs(): Long? = tAEnd?.let { it - tTurnEnd }
    fun repliesLatencyMs(): Long? = tBEnd?.let { it - tTurnEnd }

    fun summary(): String {
        val a = transcriptLatencyMs()?.let { "transcript ${it}ms" } ?: "transcript –"
        val b = repliesLatencyMs()?.let { "replies ${it}ms" } ?: "replies –"
        val flags = listOfNotNull(
            "cancelled".takeIf { cancelled }, "timeout".takeIf { timedOut }, "parseFailed".takeIf { parseFailed }, "repaired".takeIf { repaired },
        ).joinToString(" ")
        return "#$turnId ${audioMs}ms audio → $a, $b ($backend/$shape) $flags".trim()
    }

    fun toCsvRow(): String = listOf(
        turnId, tTurnEnd, audioMs, reason, tStageAStart, tAFirstToken, tAEnd, tRetrieved, tBStart, tBFirstToken, tBEnd,
        tRendered, tokensA, tokensB, decodeTpsA, decodeTpsB, backend, shape, cancelled, timedOut, parseFailed, repaired,
    ).joinToString(",") { it?.toString() ?: "" }

    companion object {
        const val CSV_HEADER =
            "turnId,tTurnEnd,audioMs,reason,tStageAStart,tAFirstToken,tAEnd,tRetrieved,tBStart,tBFirstToken,tBEnd," +
                "tRendered,tokensA,tokensB,decodeTpsA,decodeTpsB,backend,shape,cancelled,timedOut,parseFailed,repaired"
    }
}

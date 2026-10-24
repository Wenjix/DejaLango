package dev.dejalango.engine.turn

/**
 * Frame-level speech probability. Implementations are stateful (Silero carries LSTM state
 * between 32 ms frames), so one instance serves one audio stream and must be [reset] between streams.
 */
interface VoiceActivityDetector {
    /** Probability in [0, 1] that [pcm] (one 512-sample frame at 16 kHz) contains speech. */
    fun probability(pcm: FloatArray): Float
    fun reset()
    fun close() {}
}

/** Energy-only detector for tests, fixtures, and as a last-resort fallback if the ONNX model is missing. */
class EnergyVad(private val speechRms: Float = 0.02f) : VoiceActivityDetector {
    override fun probability(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) sum += (s * s).toDouble()
        val rms = Math.sqrt(sum / pcm.size).toFloat()
        // Smooth ramp: 0 at silence, 1 at 2× the threshold.
        return (rms / (2f * speechRms)).coerceIn(0f, 1f)
    }

    override fun reset() {}
}

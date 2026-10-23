package dev.dejalango.app.platform.audio

import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.audio.AudioSource
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.audio.RingBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a WAV through the identical pipeline the microphone feeds. This is the latency benchmark,
 * the UI test, and the fallback if the room is loud at 3 pm.
 *
 * @param paced true = real time (32 ms per frame), false = as fast as the consumer takes frames.
 */
class WavFixtureAudioSource(
    private val wavBytes: ByteArray,
    private val paced: Boolean = true,
    private val loop: Boolean = false,
    private val clock: Clock = Clock.system(),
) : AudioSource {

    override val sampleRate: Int = RingBuffer.DEFAULT_SAMPLE_RATE

    @Volatile
    private var running = false

    private val samples: FloatArray by lazy {
        val pcm = Wav.decode(wavBytes)
        Wav.resample(pcm.samples, pcm.sampleRate, sampleRate)
    }

    override val frames: Flow<AudioFrame> = flow {
        val t0 = clock.nowMs()
        var frameIndex = 0L
        do {
            var i = 0
            while (running && i + RingBuffer.FRAME_SAMPLES <= samples.size) {
                val pcm = samples.copyOfRange(i, i + RingBuffer.FRAME_SAMPLES)
                emit(AudioFrame(pcm, t0 + frameIndex * 1000L * RingBuffer.FRAME_SAMPLES / sampleRate))
                frameIndex++
                i += RingBuffer.FRAME_SAMPLES
                if (paced) delay(1000L * RingBuffer.FRAME_SAMPLES / sampleRate)
            }
            // A second of silence so the last turn closes.
            repeat(32) {
                if (!running) return@flow
                emit(AudioFrame(FloatArray(RingBuffer.FRAME_SAMPLES), t0 + frameIndex * 1000L * RingBuffer.FRAME_SAMPLES / sampleRate))
                frameIndex++
                if (paced) delay(1000L * RingBuffer.FRAME_SAMPLES / sampleRate)
            }
        } while (running && loop)
    }

    override suspend fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }
}

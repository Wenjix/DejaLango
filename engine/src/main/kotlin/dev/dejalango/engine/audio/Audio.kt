package dev.dejalango.engine.audio

import kotlinx.coroutines.flow.Flow

/** Wall-clock source. Injected so tests and traces are deterministic. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        fun system(): Clock = Clock { System.currentTimeMillis() }
    }
}

/**
 * One frame of mono PCM, normalized to [-1, 1].
 *
 * [tMs] is the timestamp of the frame's first sample. Sources must stamp frames from their
 * sample counter (start + framesSoFar * frameMs), not from the wall clock, so that
 * [RingBuffer.slice] maps milliseconds to samples exactly.
 */
class AudioFrame(val pcm: FloatArray, val tMs: Long) {
    fun endMs(sampleRate: Int): Long = tMs + pcm.size * 1000L / sampleRate
}

/** A microphone, a WAV fixture, a glasses audio stream — anything that yields 16 kHz frames. */
interface AudioSource {
    val sampleRate: Int
    val frames: Flow<AudioFrame>
    suspend fun start()
    fun stop()
}

/** A slice of audio handed to the model: one turn, or a trailing window in word-finding mode. */
class AudioTurn(val pcm: FloatArray, val startMs: Long, val endMs: Long, val sampleRate: Int) {
    val durationMs: Long get() = endMs - startMs
    override fun toString(): String = "AudioTurn(${durationMs} ms @ $startMs, ${pcm.size} samples)"
}

/**
 * Circular buffer holding the last [seconds] of audio. Gemma 4 accepts at most 30 s of audio
 * per request, so the buffer is the hard ceiling on what a turn can contain.
 *
 * Time ↔ sample mapping assumes contiguous frames from a sample-stamped source (see [AudioFrame]).
 */
class RingBuffer(val sampleRate: Int = DEFAULT_SAMPLE_RATE, seconds: Int = 30) {
    private val capacity = sampleRate * seconds
    private val buf = FloatArray(capacity)

    /** Total samples ever appended. */
    private var written = 0L
    private var originMs: Long? = null
    var droppedSamples: Long = 0
        private set

    val isEmpty: Boolean get() = written == 0L

    /** Timestamp of the newest sample's end, or null before the first frame. */
    val endMs: Long? get() = originMs?.let { it + written * 1000L / sampleRate }

    /** Oldest timestamp still in the buffer. */
    val startMs: Long? get() = originMs?.let { it + maxOf(0L, written - capacity) * 1000L / sampleRate }

    @Synchronized
    fun append(frame: AudioFrame) {
        if (originMs == null) originMs = frame.tMs
        val frameIndex = (frame.tMs - originMs!!) * sampleRate / 1000L
        val missing = (frameIndex - written).coerceAtLeast(0)
        if (missing > 0) {
            droppedSamples += missing
            if (missing >= capacity) {
                buf.fill(0f)
                written += missing
            } else repeat(missing.toInt()) { buf[(written++ % capacity).toInt()] = 0f }
        }
        val overlap = (written - frameIndex).coerceAtLeast(0).coerceAtMost(frame.pcm.size.toLong()).toInt()
        var pos = (written % capacity).toInt()
        for (i in overlap until frame.pcm.size) {
            buf[pos] = frame.pcm[i]
            pos++
            if (pos == capacity) pos = 0
        }
        written += frame.pcm.size - overlap
    }

    /** Copy of the samples between two timestamps, clamped to what the buffer still holds. */
    @Synchronized
    fun slice(fromMs: Long, toMs: Long): FloatArray {
        val origin = originMs ?: return FloatArray(0)
        val oldest = maxOf(0L, written - capacity)
        val from = (((fromMs - origin) * sampleRate) / 1000L).coerceIn(oldest, written)
        val to = (((toMs - origin) * sampleRate) / 1000L).coerceIn(oldest, written)
        if (to <= from) return FloatArray(0)
        val out = FloatArray((to - from).toInt())
        var pos = (from % capacity).toInt()
        for (i in out.indices) {
            out[i] = buf[pos]
            pos++
            if (pos == capacity) pos = 0
        }
        return out
    }

    fun turn(fromMs: Long, toMs: Long): AudioTurn = AudioTurn(slice(fromMs, toMs), fromMs, toMs, sampleRate)

    @Synchronized
    fun reset() {
        written = 0L
        originMs = null
        droppedSamples = 0
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512 // 32 ms at 16 kHz — Silero's native frame
    }
}

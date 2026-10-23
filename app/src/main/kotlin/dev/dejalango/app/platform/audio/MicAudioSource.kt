package dev.dejalango.app.platform.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.dejalango.engine.audio.AudioFrame
import dev.dejalango.engine.audio.AudioSource
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.audio.RingBuffer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The phone microphone as 16 kHz / 512-sample frames.
 *
 * Uses the VOICE_RECOGNITION source to avoid AGC/echo processing fighting the VAD,
 * a buffer four times the minimum so GPU stalls don't overrun, no per-frame logging or stats
 * allocations on the audio thread, and frames stamped from the sample counter so the ring buffer's
 * time→sample mapping is exact.
 */
class MicAudioSource(private val clock: Clock = Clock.system()) : AudioSource {

    override val sampleRate: Int = RingBuffer.DEFAULT_SAMPLE_RATE

    private val _frames = MutableSharedFlow<AudioFrame>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: Flow<AudioFrame> = _frames

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    var dropped: Long = 0
        private set

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the activity before the service starts
    override suspend fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 4, RingBuffer.FRAME_SAMPLES * 2 * 8)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize (permission?)" }
        record = r
        running = true
        r.startRecording()
        thread = Thread({ loop(r) }, "dejalango-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        Log.i(TAG, "mic started: ${sampleRate} Hz, buffer $bufBytes B")
    }

    private fun loop(r: AudioRecord) {
        val buf = ShortArray(RingBuffer.FRAME_SAMPLES)
        val t0 = clock.nowMs()
        var frames = 0L
        while (running) {
            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read error $n")
                break
            }
            if (n < buf.size) continue
            val pcm = FloatArray(buf.size) { buf[it] / 32768f }
            val tMs = t0 + frames * 1000L * buf.size / sampleRate
            if (!_frames.tryEmit(AudioFrame(pcm, tMs))) dropped++
            frames++
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.runCatching { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }
        record = null
        Log.i(TAG, "mic stopped (dropped frames: $dropped)")
    }

    private companion object {
        const val TAG = "MicAudioSource"
    }
}

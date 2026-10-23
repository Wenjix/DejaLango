package dev.dejalango.app.platform.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE support: 16-bit PCM in, 16-bit PCM out. Fixtures and the model adapter use it. */
object Wav {
    class Pcm(val sampleRate: Int, val channels: Int, val samples: FloatArray)

    /** Parses a 16-bit PCM WAV. Multi-channel input is averaged to mono. */
    fun decode(bytes: ByteArray): Pcm {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var data: Pair<Int, Int>? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val format = bb.getShort(body).toInt()
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                    require(format == 1 || format == 0xFFFE) { "only PCM WAV is supported (format=$format)" }
                    require(bits == 16) { "only 16-bit WAV is supported (bits=$bits)" }
                }
                "data" -> data = body to minOf(size, bytes.size - body)
            }
            pos = body + size + (size and 1)
        }
        val (start, len) = data ?: error("WAV has no data chunk")
        require(sampleRate > 0 && channels > 0) { "WAV has no fmt chunk" }
        val frames = len / (2 * channels)
        val out = FloatArray(frames)
        var p = start
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += bb.getShort(p).toFloat() / 32768f
                p += 2
            }
            out[i] = acc / channels
        }
        return Pcm(sampleRate, channels, out)
    }

    /** Encodes mono float samples in [-1, 1] as a 16-bit PCM WAV. */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataLen)
        out.write(h.array())
        val d = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) d.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(d.array())
        return out.toByteArray()
    }

    /** Nearest-neighbour resample for fixtures recorded at other rates. Good enough for VAD and Gemma. */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return samples
        val n = (samples.size.toLong() * to / from).toInt()
        return FloatArray(n) { i -> samples[minOf(samples.size - 1, (i.toLong() * from / to).toInt())] }
    }
}

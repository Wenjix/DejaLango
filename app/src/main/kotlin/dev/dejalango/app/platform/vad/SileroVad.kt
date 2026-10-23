package dev.dejalango.app.platform.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.dejalango.engine.turn.VoiceActivityDetector
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 on ONNX Runtime. The model ships as a 2.2 MB asset from the Silero release.
 *
 * v5 graph (verified against the bundled file): inputs `input` [1, 576] = 64-sample context + 512-sample
 * frame, `state` [2, 1, 128], `sr` int64 scalar; outputs `output` [1, 1], `stateN` [2, 1, 128].
 * The LSTM state and the 64-sample context carry across calls, so one instance per audio stream.
 *
 * Silero VAD is MIT licensed, © Silero Team (see THIRD_PARTY.md).
 */
class SileroVad(modelBytes: ByteArray, private val sampleRate: Int = 16_000) : VoiceActivityDetector {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        },
    )
    private val v5: Boolean = session.inputNames.contains("state")
    private val stateSize = if (v5) 2 * 1 * 128 else 2 * 1 * 64
    private var state = FloatArray(stateSize)
    private var stateC = FloatArray(stateSize) // v4 only
    private val context = FloatArray(CONTEXT)
    private val srTensor: OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf())

    @Volatile
    var lastProbability: Float = 0f
        private set

    override fun probability(pcm: FloatArray): Float {
        require(pcm.size == FRAME) { "Silero expects $FRAME samples, got ${pcm.size}" }
        val input = if (v5) FloatArray(CONTEXT + FRAME).also { context.copyInto(it, 0); pcm.copyInto(it, CONTEXT) } else pcm
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
        val inputs = HashMap<String, OnnxTensor>(4)
        inputs["input"] = inputTensor
        inputs["sr"] = srTensor
        val stateTensors = ArrayList<OnnxTensor>(2)
        if (v5) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).also { inputs["state"] = it; stateTensors += it }
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 64)).also { inputs["h"] = it; stateTensors += it }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(stateC), longArrayOf(2, 1, 64)).also { inputs["c"] = it; stateTensors += it }
        }
        try {
            session.run(inputs).use { result ->
                val out = result[0].value
                val prob = when (out) {
                    is Array<*> -> ((out[0] as FloatArray)[0])
                    is FloatArray -> out[0]
                    else -> 0f
                }
                if (v5) {
                    (result.get("stateN").get() as OnnxTensor).floatBuffer.get(state)
                } else {
                    (result.get("hn").get() as OnnxTensor).floatBuffer.get(state)
                    (result.get("cn").get() as OnnxTensor).floatBuffer.get(stateC)
                }
                lastProbability = prob.coerceIn(0f, 1f)
            }
        } finally {
            inputTensor.close()
            stateTensors.forEach { it.close() }
        }
        if (v5) pcm.copyInto(context, 0, FRAME - CONTEXT, FRAME)
        return lastProbability
    }

    override fun reset() {
        state.fill(0f)
        stateC.fill(0f)
        context.fill(0f)
        lastProbability = 0f
    }

    fun close() {
        srTensor.close()
        session.close()
    }

    companion object {
        const val FRAME = 512
        const val CONTEXT = 64
    }
}

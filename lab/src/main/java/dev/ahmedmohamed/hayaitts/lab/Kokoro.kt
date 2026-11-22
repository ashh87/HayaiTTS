package dev.ahmedmohamed.hayaitts.lab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * The expressive Kokoro model.onnx (kokoro tools export_expressive.py) run with ONNX Runtime, so any voice,
 * mixed on the phone, can drive it. Inputs as KPipeline.infer: tokens [0, ids…, 0], style = the voice row for
 * the chunk's length (pack[len(phonemes) - 1]) followed by the control vector, and speed.
 */
class Kokoro(model: File, tokensTxt: String) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.path, OrtSession.SessionOptions())
    private val vocab: Map<Int, Long> = tokensTxt.lines().filter { it.isNotEmpty() }.associate { line ->
        val sp = line.lastIndexOf(' ')
        line.substring(0, sp).codePointAt(0) to line.substring(sp + 1).trim().toLong()
    }

    class Output(val audio: FloatArray, val durations: LongArray)

    /** Token ids for one chunk's phoneme code points; symbols the model doesn't know are dropped, as KPipeline does. */
    fun ids(codepoints: IntArray): LongArray = codepoints.asList().mapNotNull { vocab[it] }.toLongArray()

    /** [voice] is a 510 x 256 pack (row-major), [controls] the N_CONTROLS values (expressive.controls()). */
    fun run(codepoints: IntArray, voice: FloatArray, controls: FloatArray, speed: Float): Output {
        require(voice.size == ROWS * STYLE) { "voice must be $ROWS x $STYLE floats" }
        val row = (codepoints.size - 1).coerceIn(0, ROWS - 1)
        val style = FloatArray(STYLE + controls.size)
        voice.copyInto(style, 0, row * STYLE, (row + 1) * STYLE)
        controls.copyInto(style, STYLE)
        return run(longArrayOf(0) + ids(codepoints) + 0, style, speed)
    }

    /** One call of the model: [tokens] already padded, [style] the full 264-wide row. */
    fun run(tokens: LongArray, style: FloatArray, speed: Float): Output =
        OnnxTensor.createTensor(env, arrayOf(tokens)).use { t ->
            OnnxTensor.createTensor(env, arrayOf(style)).use { s ->
                OnnxTensor.createTensor(env, floatArrayOf(speed)).use { sp ->
                    session.run(mapOf("tokens" to t, "style" to s, "speed" to sp)).use { r ->
                        Output(r.get("audio").get().value as FloatArray, r.get("durations").get().value as LongArray)
                    }
                }
            }
        }

    override fun close() = session.close()

    companion object {
        const val ROWS = 510
        const val STYLE = 256
        const val SAMPLE_RATE = 24000
    }
}

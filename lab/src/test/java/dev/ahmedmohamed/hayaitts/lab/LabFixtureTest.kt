package dev.ahmedmohamed.hayaitts.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Against lab_fixture.json, written from the PC model by the kokoro tools' lab_fixture.py. */
class LabFixtureTest {
    private val fixture = Json.parseToJsonElement(resource("lab_fixture.json").readText()).jsonObject

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource(name).toURI())

    @Test
    fun `creak pulses match creak py`() {
        for (c in fixture.getValue("creak").jsonArray.map { it.jsonObject }) {
            val bytes = resource(c.getValue("file").jsonPrimitive.content).readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val audio = FloatArray(fb.remaining()).also { fb.get(it) }
            assertEquals(c.toString(), c.getValue("pulses").jsonPrimitive.int, Creak.pulses(audio))
        }
    }

    @Test
    fun `onnx runtime gives the torch durations`() {
        val dir = File(System.getenv("KOKORO_BUNDLE") ?: "../../kokoro/build/bf_harriet-expressive")
        assumeTrue("no model at $dir", File(dir, "model.onnx").isFile)
        Kokoro(File(dir, "model.onnx"), File(dir, "tokens.txt").readText()).use { k ->
            for (c in fixture.getValue("chunks").jsonArray.map { it.jsonObject }) {
                val ps = c.getValue("phonemes").jsonPrimitive.content
                val ids = c.getValue("ids").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                assertArrayEquals(ps, ids, k.ids(ps.codePoints().toArray()))
                val style = c.getValue("style").jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
                val speed = c.getValue("speed").jsonPrimitive.double.toFloat()
                val want = c.getValue("durations").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                assertArrayEquals(ps, want, k.run(longArrayOf(0) + ids + 0, style, speed).durations)
            }
        }
    }
}

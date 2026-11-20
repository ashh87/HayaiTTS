package dev.ahmedmohamed.hayaitts.tts.markup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden cases shared with the kokoro tools' markup.py (kept identical; regenerate there). */
class MarkupTest {

    private fun JsonObject.toSegment() = Markup.Segment(
        text = getValue("text").jsonPrimitive.content,
        emotion = getValue("emotion").jsonPrimitive.contentOrNull,
        intensity = getValue("intensity").jsonPrimitive.double,
        rate = getValue("rate").jsonPrimitive.double,
        pitch = getValue("pitch").jsonPrimitive.double,
        volume = getValue("volume").jsonPrimitive.double,
        pauseMs = getValue("pause_ms").jsonPrimitive.int,
    )

    private fun Markup.Segment.rounded() = copy(
        intensity = Math.round(intensity * 1000) / 1000.0,
        rate = Math.round(rate * 1000) / 1000.0,
        pitch = Math.round(pitch * 1000) / 1000.0,
        volume = Math.round(volume * 1000) / 1000.0,
    )

    @Test
    fun `markup matches the shared golden cases`() {
        val json = javaClass.classLoader!!.getResource("markup_cases.json")!!.readText()
        val cases = Json.parseToJsonElement(json).jsonArray
        for (case in cases) {
            val input = case.jsonObject.getValue("input").jsonPrimitive.content
            val want = case.jsonObject.getValue("segments").jsonArray.map { it.jsonObject.toSegment().rounded() }
            assertEquals(input, want, Markup.parse(input).map { it.rounded() })
        }
    }
}

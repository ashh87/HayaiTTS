package dev.ahmedmohamed.hayaitts.tts.g2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Golden cases from the kokoro tools' test_accent.py (accent_cases.json and the accents folder; kept identical, regenerate there). */
class AccentRulesTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val lexsets by lazy { PackedTable.file(File(MisakiEnglishTest.ASSETS, "accent_lexsets.kv")) }

    private fun g2p(accent: String) = MisakiEnglish(
        true,
        MisakiEnglish.Dict(PackedTable.file(File(MisakiEnglishTest.ASSETS, "gb_gold.kv"))),
        MisakiEnglish.Dict(PackedTable.file(File(MisakiEnglishTest.ASSETS, "gb_silver.kv"))),
        MisakiEnglishTest.tokenizer,
        MisakiEnglishTest.tagger,
    ) { null }.apply { this.accent = AccentRules(text("accents/$accent.json"), lexsets) }

    @Test
    fun `transplant keeps marks through re-stressing and suffixes`() {
        assertEquals("kˈɑːRz", AccentRules.transplant("kˈɑːR", "kˈɑːz"))
        assertEquals("bˌBɑːθ", AccentRules.transplant("bˈBɑːθ", "bˌɑːθ"))
        assertNull(AccentRules.transplant("kˈɑːR", "kˈat"))
    }

    @Test
    fun `accents match the shared golden cases`() {
        val g = mapOf("scottish" to g2p("scottish"), "northern" to g2p("northern"))
        val bad = mutableListOf<String>()
        for (c in Json.parseToJsonElement(text("accent_cases.json")).jsonArray) {
            val o = c.jsonObject
            val accent = o.getValue("accent").jsonPrimitive.content
            val input = o.getValue("input").jsonPrimitive.content
            val want = o.getValue("phonemes").jsonPrimitive.content
            val got = g.getValue(accent).phonemize(input)
            if (got != want) bad += "$accent | $input\n  want $want\n  got  $got"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }
}

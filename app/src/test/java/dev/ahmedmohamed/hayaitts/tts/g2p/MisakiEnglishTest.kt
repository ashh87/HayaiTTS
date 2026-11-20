package dev.ahmedmohamed.hayaitts.tts.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the Kotlin port against misaki's own output (fixtures in test/resources/g2p). */
class MisakiEnglishTest {

    private class Row(val british: Boolean, val text: String, val ps: String, val toks: List<EnglishTokenizer.Token>, val tags: List<String>)

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource("g2p/$name")!!.toURI())

    private val rows = resource("golden.tsv").readLines().map { line ->
        val (accent, text, ps, toks) = line.split('\t')
        val triples = toks.split('\u001e').map { it.split('\u001f') }
        Row(accent == "gb", text, ps, triples.map { EnglishTokenizer.Token(it[0], it[2]) }, triples.map { it[1] })
    }

    private val espeakMisses = mutableSetOf<String>()

    private fun g2p(british: Boolean): MisakiEnglish {
        val voice = if (british) "en" else "en-us"
        val raw = resource("espeak.tsv").readLines().map { it.split('\t') }.filter { it[0] == voice }.associate { it[1] to it[2] }
        val accent = if (british) "gb" else "us"
        fun dict(name: String) = PackedTable.file(File(ASSETS, "${accent}_$name.kv"))
        return MisakiEnglish(
            british,
            MisakiEnglish.Dict(dict("gold")),
            MisakiEnglish.Dict(dict("silver")),
            tokenizer,
            tagger,
        ) { text -> raw[text] ?: "".also { espeakMisses += text } }
    }

    private fun parity(withSpacyTags: Boolean): Double {
        var same = 0
        val mismatches = mutableListOf<String>()
        for (british in listOf(true, false)) {
            val g = g2p(british)
            for (r in rows.filter { it.british == british }) {
                val got = if (withSpacyTags) g.phonemize(r.toks, r.tags) else g.phonemize(r.text)
                if (got == r.ps.trim()) same++ else mismatches += "${if (british) "gb" else "us"} | ${r.text}\n  want ${r.ps.trim()}\n  got  $got"
            }
        }
        println("parity (spaCy tags=$withSpacyTags): $same/${rows.size}; espeak misses: ${espeakMisses.take(10)}")
        mismatches.take(40).forEach(::println)
        return same.toDouble() / rows.size
    }

    @Test
    fun `port matches misaki given spaCy's tokens and tags`() {
        assertTrue(parity(withSpacyTags = true) >= 0.99)
    }

    @Test
    fun `full pipeline with our tokenizer and tagger stays close to misaki`() {
        assertTrue(parity(withSpacyTags = false) >= 0.95)
    }

    @Test
    fun `homographs follow the tagger`() {
        val g = g2p(british = true)
        assertEquals("ˌI ɹˈɛd ɪt jˈɛstədA; juː ɹˈɛd ɪt tʊmˈɒɹQ.", g.phonemize("I read it yesterday; you read it tomorrow."))
        val lead = g.phonemize("He used to live near the lead mine, where they used lead pipes.")
        assertTrue(lead, lead.startsWith("hˌiː jˈuːst tə lˈɪv"))
    }

    @Test
    fun `custom words override the dictionary in any case`() {
        val tsv = File.createTempFile("pronunciations", ".tsv").apply {
            deleteOnExit()
            writeText("# comment\nniamh\tnˈiːv\nnormalé  nˌɔːmˈɑɑlˈɛː\n")
        }
        val g = MisakiEnglish(
            true,
            MisakiEnglish.Dict(PackedTable.file(File(ASSETS, "gb_gold.kv")), MisakiAssets.loadCustomWords(tsv)),
            MisakiEnglish.Dict(null),
            tokenizer,
            tagger,
        ) { null }
        assertEquals("həlˈQ nˈiːv.", g.phonemize("Hello Niamh."))
        assertEquals("nˌɔːmˈɑɑlˈɛː, nˌɔːmˈɑɑlˈɛː.", g.phonemize("Normalé, normalé."))
    }

    @Test
    fun `long phoneme runs are cut at punctuation under sherpa's limit`() {
        val long = (1..120).joinToString(", ") { "wˈʌn" } + ". ʃˈɔːt."
        val chunks = dev.ahmedmohamed.hayaitts.tts.MisakiFrontend.chunks(long)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(long.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertEquals("ʃˈɔːt.", chunks.last())
        assertTrue(chunks.first().endsWith(","))
    }

    @Test
    fun `number words match num2words`() {
        assertEquals("one thousand, two hundred and fifty", NumberWords.cardinal(1250))
        assertEquals("nineteen eighty-four", NumberWords.year(1984))
        assertEquals("nineteen oh-five", NumberWords.year(1905))
        assertEquals("two thousand and five", NumberWords.year(2005))
        assertEquals("twenty-first", NumberWords.ordinal(21.toBigInteger()))
        assertEquals("three point one four", NumberWords.decimal("3.140"))
        assertEquals("three", NumberWords.decimal("3.0"))
    }

    private companion object {
        val ASSETS = File("src/main/assets/g2p")
        val tokenizer by lazy { EnglishTokenizer(File(ASSETS, "en_tokenizer.json").readText()) }
        val tagger by lazy { PosTagger(PackedTable.file(File(ASSETS, "pos_tagger.kv"))) }
    }
}

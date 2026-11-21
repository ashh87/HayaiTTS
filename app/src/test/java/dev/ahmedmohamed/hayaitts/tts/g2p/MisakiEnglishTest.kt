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
            writeText("# comment\nniamh\tnˈiːv\nnormalé  nˌɔːmˈɑɑlˈɛː\nPOI\tpˌiːˌQˈI\nGPS\tʤˌiːpiːˈɛs\n")
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
        // acronyms in capitals only (expected strings from the kokoro tools' lexicon.add_custom_words)
        assertEquals("pˌiːˌQˈI əhˈɛd.", g.phonemize("POI ahead."))
        assertEquals("tˈuː pˌiːˌQˈIz nˌɪəbˈI.", g.phonemize("Two POIs nearby."))
        assertEquals("ˈiːt sˌʌm pˈY.", g.phonemize("Eat some poi."))
        // the s matters: GPs (doctors) isn't GPS, and GPSs is GPS plus a plural
        assertEquals("tˈuː ʤˌiːpˈiːz.", g.phonemize("Two GPs."))
        assertEquals("ðə ʤˌiːpiːˈɛs.", g.phonemize("The GPS."))
        assertEquals("tˈuː ʤˌiːpiːˈɛsɪz.", g.phonemize("Two GPSs."))
    }

    @Test
    fun `markdown links set phonemes and stress as misaki does`() {
        // Expected: misaki 0.9.4 via KPipeline(lang_code="b").
        val g = g2p(british = true)
        assertEquals("sˈA nˈiːv plˈiːz.", g.phonemize("Say [Niamh](/nˈiːv/) please."))
        assertEquals("ˌI ɹˈɪəli mˈiːn ˌɪt nˈW.", g.phonemize("I [really](+2) mean [it](+1) [now](+1)."))
        assertEquals("ɹˈiːd ðə ɹɪkˈɔːd əlˈWd.", g.phonemize("Read [the](-1) [record](/ɹɪkˈɔːd/) aloud."))
        assertEquals("ˌɪt wɒz ɹɪdjˌuːst ɐ lˈɒt.", g.phonemize("It was [reduced](-1) a lot."))
        assertEquals("ðə kˈat sˈat.", g.phonemize("The [cat](0.5) sat."))
    }

    @Test
    fun `span markers land on the same phonemes as in the kokoro tools`() {
        // Expected: expressive.phonemes_with_markers (misaki 0.9.4, KPipeline lang "b").
        val g = g2p(british = true)
        val cases = listOf(
            "I \uE000really\uE001 mean it, and I \uE000\uE000never\uE001\uE001 said so." to
                "ˌI \uE000ɹˈɪəli\uE001 mˈiːn ɪt, and ˌI \uE000\uE000nˈɛvə\uE001\uE001 sˈɛd sˌQ.",
            "Say \uE000[Niamh](/nˈiːv/)\uE001 twice." to "sˈA \uE000nˈiːv\uE001 twˈIs.",
            "Put it back where you found it, \uE002\uE002 right now!\uE003\uE003 Thank you." to
                "pˌʊt ɪt bˈak wˌɛː juː fˈWnd ɪt, \uE002\uE002ɹˈIt nˈW! \uE003\uE003θˈaŋk juː.",
            "It cost \uE000£1,200\uE001 in 1984." to
                "ˌɪt kˈɒst \uE000wˈʌn θˈWzᵊnd tˈuː hˈʌndɹəd pˈWndz\uE001 ɪn nˌIntˈiːn ˈAti fˈɔː.",
            "\uE000Don't\uE001 you \uE000dare\uE001, it's \uE000not\uE001 yours." to
                "\uE000dˈQnt\uE001 juː \uE000dˈɛː\uE001, ɪts \uE000nˌɒt\uE001 jˈɔːz.",
        )
        for ((text, want) in cases) assertEquals(text, want, g.phonemize(text))
    }

    @Test
    fun `terms of address after a closing comma lose stress and get a longer comma`() {
        val g = g2p(british = true)
        val cases = listOf( // expected from the kokoro tools' expressive.phonemes_with_markers
            "That's all I've got, love." to "ðˈats ˈɔːl ˌIv ɡˈɒt,,, lʌv.",
            "Cheers, mate!" to "ʧˈɪəz,, mAt!",
            "Thanks, darling." to "θˈaŋks,, dɑːlɪŋ.",
            "Love, it's late." to "lˈʌv, ɪts lˈAt.",
            "I love my mate." to "ˌI lˈʌv mI mˈAt.",
            "Take this, love, and go." to "tˈAk ðˈɪs, lˈʌv, and ɡˌQ.",
            "All I've got, [love](/lˈʌv/)." to "ˈɔːl ˌIv ɡˈɒt, lˈʌv.",
        )
        for ((text, want) in cases) assertEquals(text, want, g.phonemize(text))
    }

    @Test
    fun `chunks carry open emphasis and level markers forward`() {
        val carried = dev.ahmedmohamed.hayaitts.tts.MisakiFrontend.carryMarkers(
            listOf("a \uE000b \uE002\uE002c.", "d\uE001 e.", "f \uE003g.", "h."),
        )
        assertEquals(
            listOf("a \uE000b \uE002\uE002c.", "\uE000\uE002\uE002d\uE001 e.", "\uE002\uE002f \uE003g.", "\uE002h."),
            carried,
        )
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

    internal companion object {
        val ASSETS = File("src/main/assets/g2p")
        val tokenizer by lazy { EnglishTokenizer(File(ASSETS, "en_tokenizer.json").readText()) }
        val tagger by lazy { PosTagger(PackedTable.file(File(ASSETS, "pos_tagger.kv"))) }
    }
}

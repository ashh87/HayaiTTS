package dev.ahmedmohamed.hayaitts.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class LexiconFirstFrontendTest {

    private val espeakCalls = mutableListOf<String>()
    private val frontend = LexiconFirstFrontend(
        lexicon = mapOf("hello" to "həlˈO", "niamh" to "nˈiːv", "don't" to "dˈOnt"),
        espeak = { espeakCalls += it; listOf("<$it>") },
    )

    @Test
    fun `lexicon words win and out-of-lexicon runs go to espeak together`() {
        assertEquals("həlˈO, <dear 42 friends> nˈiːv!", frontend.phonemizeSentence("Hello, dear 42 friends Niamh!"))
        assertEquals(listOf("dear 42 friends"), espeakCalls)
    }

    @Test
    fun `curly apostrophes match the lexicon`() {
        assertEquals("dˈOnt", frontend.phonemizeSentence("Don’t"))
        assertEquals(emptyList<String>(), espeakCalls)
    }

    @Test
    fun `one codepoint array per sentence`() {
        val out = frontend.phonemize("Hello. Niamh?  ")
        assertEquals(listOf("həlˈO.", "nˈiːv?"), out.map { String(it, 0, it.size) })
    }
}

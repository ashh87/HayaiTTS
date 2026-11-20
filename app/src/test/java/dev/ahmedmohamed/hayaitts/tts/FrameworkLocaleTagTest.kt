package dev.ahmedmohamed.hayaitts.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameworkLocaleTagTest {
    @Test
    fun `framework ISO-3 codes become two-letter tags`() {
        assertEquals("en-GB", frameworkLocaleTag("eng", "GBR"))
        assertEquals("en-US", frameworkLocaleTag("eng", "USA"))
        assertEquals("en", frameworkLocaleTag("eng", ""))
        assertEquals("pt-BR", frameworkLocaleTag("por", "BRA"))
    }

    @Test
    fun `two-letter input passes through`() {
        assertEquals("en-GB", frameworkLocaleTag("en", "GB"))
        assertEquals("en", frameworkLocaleTag("en-GB", null))
        assertEquals("", frameworkLocaleTag(null, "GB"))
    }
}

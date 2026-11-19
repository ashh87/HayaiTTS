package dev.ahmedmohamed.hayaitts.data.custom

import dev.ahmedmohamed.hayaitts.data.custom.CustomBundleAnalyzer.ArchiveKind
import dev.ahmedmohamed.hayaitts.data.custom.CustomBundleAnalyzer.Companion.sniffArchive
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveSniffTest {

    private fun bytes(vararg parts: Pair<Int, String>): ByteArray {
        val b = ByteArray(CustomBundleAnalyzer.SNIFF_BYTES)
        for ((offset, s) in parts) s.forEachIndexed { i, c -> b[offset + i] = c.code.toByte() }
        return b
    }

    @Test
    fun `bzip2 header is tar bz2`() = assertEquals(ArchiveKind.TAR_BZ2, sniffArchive(bytes(0 to "BZh91AY&SY")))

    @Test
    fun `zip local header is zip`() = assertEquals(ArchiveKind.ZIP, sniffArchive(bytes(0 to "PK\u0003\u0004")))

    @Test
    fun `ustar at 257 is tar`() = assertEquals(ArchiveKind.TAR, sniffArchive(bytes(0 to "model.onnx", 257 to "ustar")))

    @Test
    fun `protobuf ir_version tag is bare onnx`() =
        assertEquals(ArchiveKind.BARE_ONNX, sniffArchive(byteArrayOf(0x08, 0x07, 0x12)))

    @Test
    fun `unknown and short inputs stay unknown`() {
        assertEquals(ArchiveKind.UNKNOWN, sniffArchive(bytes(0 to "%PDF-1.7")))
        assertEquals(ArchiveKind.UNKNOWN, sniffArchive(ByteArray(0)))
        assertEquals(ArchiveKind.UNKNOWN, sniffArchive("BZ".toByteArray()))
    }
}

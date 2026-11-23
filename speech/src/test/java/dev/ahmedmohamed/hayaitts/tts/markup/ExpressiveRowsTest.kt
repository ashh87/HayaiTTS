package dev.ahmedmohamed.hayaitts.tts.markup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ExpressiveRowsTest {

    // Same layout as build_package.py --expressive: neutral x 5 pitches, then each emotion x 50/100 % x 5 pitches.
    private val rows = File.createTempFile("expr", "").let { dir ->
        dir.delete(); dir.mkdir(); dir.deleteOnExit()
        val pitches = listOf(0, -4, -2, 2, 4)
        val lines = pitches.map { "neutral 100 $it" } +
            listOf("happy", "sad").flatMap { e -> listOf(50, 100).flatMap { lv -> pitches.map { "$e $lv $it" } } }
        File(dir, ExpressiveRows.FILE).writeText("# header\n" + lines.withIndex().joinToString("\n") { (i, l) -> "$i $l" })
        ExpressiveRows.read(dir)!!
    }

    @Test
    fun `nearest row by emotion, pitch, then intensity`() {
        assertEquals(0, rows.sidFor(null, 1.0, 0.0))
        assertEquals(3, rows.sidFor(null, 1.0, 1.6)) // +2
        assertEquals(10, rows.sidFor("happy", 1.0, 0.0))
        assertEquals(5, rows.sidFor("happy", 0.4, 0.0))
        assertEquals(24, rows.sidFor("sad", 0.9, 5.0)) // 100 %, +4
        assertEquals(0, rows.sidFor("happy", 0.1, 0.0)) // too faint: neutral
        assertEquals(0, rows.sidFor("angry", 1.0, 0.0)) // not in this voice: neutral
    }

    @Test
    fun `several voices - each base sid gets only its own rows`() {
        val dir = File.createTempFile("expr2", "").apply { delete(); mkdir(); deleteOnExit() }
        // build_package.py --with: plain voices first (0, 1), then each voice's other rows
        File(dir, ExpressiveRows.FILE).writeText(
            "# base_sid sid emotion intensity% pitch\n" +
                "0 0 neutral 100 +0\n0 2 neutral 100 +2\n0 3 happy 100 +0\n" +
                "1 1 neutral 100 +0\n1 4 neutral 100 +2\n1 5 happy 100 +0\n",
        )
        val all = ExpressiveRows.read(dir)!!
        assertEquals(3, all.forBase(0)!!.sidFor("happy", 1.0, 0.0))
        assertEquals(5, all.forBase(1)!!.sidFor("happy", 1.0, 0.0))
        assertEquals(4, all.forBase(1)!!.sidFor(null, 1.0, 2.0))
        assertNull(all.forBase(2)) // an expressive row, not a voice
        assertEquals(0, rows.forBase(0)!!.sidFor(null, 1.0, 0.0)) // single-voice files are all base 0
        assertNull(rows.forBase(3))
    }

    @Test
    fun `no file, no rows`() {
        assertNull(ExpressiveRows.read(File.createTempFile("none", "").parentFile!!.resolve("missing-dir")))
    }
}

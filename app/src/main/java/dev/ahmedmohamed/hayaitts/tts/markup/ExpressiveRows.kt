package dev.ahmedmohamed.hayaitts.tts.markup

import java.io.File
import kotlin.math.abs

/**
 * A voice's `expressive.txt` (written by the kokoro tools' build_package.py --expressive):
 * one "sid emotion intensity% pitch_semitones" line per voices.bin row, rendered by the
 * expressive Kokoro model. Rows are fixed, so a segment gets the nearest one.
 */
class ExpressiveRows(private val rows: List<Row>) {

    data class Row(val sid: Int, val emotion: String?, val intensity: Int, val pitch: Int)

    /** Speaker id for this emotion/intensity/pitch; an emotion under 25 % counts as neutral. */
    fun sidFor(emotion: String?, intensity: Double, pitch: Double): Int? {
        val e = emotion?.takeIf { intensity >= 0.25 && rows.any { r -> r.emotion == it } }
        val pct = intensity * 100
        return rows.filter { it.emotion == e }
            .minWithOrNull(compareBy<Row>({ abs(it.pitch - pitch) }, { abs(it.intensity - pct) }))
            ?.sid
    }

    companion object {
        const val FILE = "expressive.txt"

        fun read(dir: File): ExpressiveRows? {
            val f = File(dir, FILE).takeIf { it.isFile } ?: return null
            val rows = f.readLines().mapNotNull { line ->
                val p = line.substringBefore('#').trim().split(Regex("\\s+"))
                if (p.size != 4) return@mapNotNull null
                val sid = p[0].toIntOrNull() ?: return@mapNotNull null
                val pct = p[2].toIntOrNull() ?: return@mapNotNull null
                val pitch = p[3].toIntOrNull() ?: return@mapNotNull null
                Row(sid, p[1].takeIf { it != "neutral" }, pct, pitch)
            }
            return ExpressiveRows(rows).takeIf { rows.isNotEmpty() }
        }
    }
}

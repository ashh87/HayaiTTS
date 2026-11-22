package dev.ahmedmohamed.hayaitts.lab

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The kokoro tools' creak.py: fry pulses in the quiet tail of a line (a trailing "a" or clicks). By ear:
 * under ~3 pulses per line sounds clean, 7+ clicky; old bf_ha 3.1. [pulses] is creak.pulses(), with
 * scipy's find_peaks(prominence=1.0, distance=4) written out.
 */
object Creak {
    const val SR = 24000
    val LINES = listOf(
        "Tonight's bins:", "Lovely weather for a cup of tea.", "Put it back.", "Is it ready?",
        "I'll see you tomorrow.", "That's all for now.",
    )

    private fun env(a: FloatArray, from: Int, ms: Int): DoubleArray {
        val n = SR * ms / 1000
        return DoubleArray((a.size - from) / n) { f ->
            var s = 0.0
            for (i in from + f * n until from + (f + 1) * n) s += a[i].toDouble() * a[i]
            sqrt(s / n)
        }
    }

    fun pulses(audio: FloatArray): Int {
        if (audio.isEmpty()) return 0
        val pk = audio.maxOf { abs(it) }.toDouble()
        val e50 = env(audio, 0, 50)
        val max50 = e50.maxOrNull() ?: return 0
        val last = e50.indices.last { e50[it] > 0.25 * max50 }
        val e = env(audio, last * SR / 20, 3).map { it / pk }
        val x = DoubleArray(e.size) { ln(e[it] + 1e-4) }
        return findPeaks(x, prominence = 1.0, distance = 4).count { e[it] > 0.005 }
    }

    /** scipy.signal.find_peaks with only distance and prominence: local maxima (plateau midpoints), then
     * the distance cut (highest first), then prominence. */
    internal fun findPeaks(x: DoubleArray, prominence: Double, distance: Int): List<Int> {
        val peaks = ArrayList<Int>()
        var i = 1
        while (i < x.size - 1) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < x.size - 1 && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    peaks += (i + ahead - 1) / 2
                    i = ahead
                }
            }
            i++
        }
        val keep = BooleanArray(peaks.size) { true }
        for (j in peaks.indices.sortedBy { x[peaks[it]] }.reversed()) {
            if (!keep[j]) continue
            var k = j - 1
            while (k >= 0 && peaks[j] - peaks[k] < distance) keep[k--] = false
            k = j + 1
            while (k < peaks.size && peaks[k] - peaks[j] < distance) keep[k++] = false
        }
        return peaks.filterIndexed { j, _ -> keep[j] }.filter { p ->
            var leftMin = x[p]
            var k = p
            while (k >= 0 && x[k] <= x[p]) { if (x[k] < leftMin) leftMin = x[k]; k-- }
            var rightMin = x[p]
            k = p
            while (k < x.size && x[k] <= x[p]) { if (x[k] < rightMin) rightMin = x[k]; k++ }
            x[p] - maxOf(leftMin, rightMin) >= prominence
        }
    }
}

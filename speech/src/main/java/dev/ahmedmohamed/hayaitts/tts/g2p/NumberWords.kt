package dev.ahmedmohamed.hayaitts.tts.g2p

import java.math.BigInteger

/** The parts of Python num2words (English) that misaki uses, with the same wording. */
object NumberWords {
    private val LOW = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = listOf(
        "thousand", "million", "billion", "trillion", "quadrillion", "quintillion", "sextillion",
        "septillion", "octillion", "nonillion", "decillion",
    )
    private val ORDS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "four" to "fourth", "five" to "fifth",
        "six" to "sixth", "seven" to "seventh", "eight" to "eighth", "nine" to "ninth", "ten" to "tenth",
        "eleven" to "eleventh", "twelve" to "twelfth",
    )
    private val THOUSAND = BigInteger.valueOf(1000)

    fun cardinal(n: BigInteger): String = if (n.signum() < 0) "minus " + words(n.negate()) else words(n)

    fun cardinal(n: Long): String = cardinal(BigInteger.valueOf(n))

    private fun words(n: BigInteger): String {
        if (n < THOUSAND) return small(n.toInt())
        var scale = 0
        var unit = THOUSAND
        while (scale + 1 < SCALES.size && n >= unit * THOUSAND) {
            unit *= THOUSAND
            scale++
        }
        val (high, rest) = n.divideAndRemainder(unit)
        val head = words(high) + " " + SCALES[scale]
        return when {
            rest.signum() == 0 -> head
            rest < BigInteger.valueOf(100) -> "$head and " + words(rest)
            else -> "$head, " + words(rest)
        }
    }

    private fun small(n: Int): String = when {
        n < 20 -> LOW[n]
        n < 100 -> TENS[n / 10] + if (n % 10 != 0) "-" + LOW[n % 10] else ""
        else -> LOW[n / 100] + " hundred" + if (n % 100 != 0) " and " + small(n % 100) else ""
    }

    fun ordinal(n: BigInteger): String {
        val out = cardinal(n).split(" ").toMutableList()
        val last = out.last().split("-").toMutableList()
        val lw = last.last().lowercase()
        last[last.lastIndex] = ORDS[lw] ?: ((if (lw.endsWith("y")) lw.dropLast(1) + "ie" else lw) + "th")
        out[out.lastIndex] = last.joinToString("-")
        return out.joinToString(" ")
    }

    fun year(n: Long): String {
        val high = n / 100
        val low = n % 100
        if (high == 0L || (high % 10 == 0L && low < 10) || high >= 100) return cardinal(n)
        val lowText = when {
            low == 0L -> "hundred"
            low < 10 -> "oh-" + cardinal(low)
            else -> cardinal(low)
        }
        return cardinal(high) + " " + lowText
    }

    /** num2words(float(s)); whole values ("3.0", "3.") read as integers, like num2words does. */
    fun decimal(s: String): String {
        val intPart = s.substringBefore('.').ifEmpty { "0" }
        val frac = s.substringAfter('.', "").trimEnd('0')
        if (frac.isEmpty()) return cardinal(BigInteger(intPart))
        return cardinal(BigInteger(intPart)) + " point " + frac.map { cardinal(it.digitToInt().toLong()) }.joinToString(" ")
    }
}

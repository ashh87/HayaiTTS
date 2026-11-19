package dev.ahmedmohamed.hayaitts.tts.g2p

/**
 * Greedy averaged-perceptron POS tagger standing in for spaCy en_core_web_sm,
 * which misaki uses to pick between pronunciations ("read", "lead", "used").
 * Trained on spaCy's own tags by tools/g2p/train_tagger.py (~96% agreement);
 * the feature code here mirrors that script exactly.
 */
class PosTagger(lines: Sequence<String>) {

    private var classes = emptyArray<String>()
    private val tagdict = HashMap<String, String>()
    private val weights = HashMap<String, Pair<IntArray, DoubleArray>>(1 shl 17)

    init {
        for (line in lines) {
            val f = line.split('\t')
            when (f[0]) {
                "C" -> classes = f.drop(1).toTypedArray()
                "T" -> tagdict[f[1]] = f[2]
                "W" -> {
                    val pairs = f[2].split(' ')
                    weights[f[1]] = IntArray(pairs.size) { pairs[it].substringBefore(':').toInt() } to
                        DoubleArray(pairs.size) { pairs[it].substringAfter(':').toDouble() }
                }
            }
        }
    }

    fun tag(words: List<String>): List<String> {
        val ctx = listOf("-S-", "-S2-") + words.map(::norm) + listOf("-E-", "-E2-")
        var prev = "-S-"
        var prev2 = "-S2-"
        val scores = DoubleArray(classes.size)
        return words.indices.map { i ->
            val t = tagdict[words[i]] ?: run {
                scores.fill(0.0)
                for (feat in features(i, words, ctx, prev, prev2)) {
                    val (cs, ws) = weights[feat] ?: continue
                    for (k in cs.indices) scores[cs[k]] += ws[k]
                }
                // Python: max(classes, key=(score, name))
                classes.indices.maxWith(compareBy<Int>({ scores[it] }, { classes[it] })).let { classes[it] }
            }
            prev2 = prev
            prev = t
            t
        }
    }

    private fun features(i0: Int, words: List<String>, ctx: List<String>, prev: String, prev2: String): List<String> {
        val w = words[i0]
        val i = i0 + 2
        return listOf(
            "b", "s3 " + ctx[i].takeLast(3), "s2 " + ctx[i].takeLast(2), "s1 " + ctx[i].takeLast(1),
            "p1 " + w[0], "sh " + shape(w),
            "t1 $prev", "t2 $prev2", "t12 $prev $prev2", "w " + ctx[i], "t1w $prev " + ctx[i],
            "w-1 " + ctx[i - 1], "s-1 " + ctx[i - 1].takeLast(3), "w-2 " + ctx[i - 2],
            "w+1 " + ctx[i + 1], "s+1 " + ctx[i + 1].takeLast(3), "w+2 " + ctx[i + 2],
            "sh+1 " + (if (i - 1 < words.size) shape(words[i - 1]) else "END"),
        )
    }

    private companion object {
        fun norm(w: String): String = when {
            '-' in w && w[0] != '-' -> "!HYPHEN"
            w.length == 4 && w.all { it.isDigit() } -> "!YEAR"
            w.isNotEmpty() && w[0].isDigit() -> "!DIGITS"
            else -> w.lowercase()
        }

        fun shape(w: String): String {
            val out = StringBuilder()
            for (ch in w) {
                val c = when {
                    ch.isUpperCase() -> 'X'
                    ch.isLowerCase() -> 'x'
                    ch.isDigit() -> 'd'
                    else -> ch
                }
                if (out.length >= 2 && out[out.length - 1] == c && out[out.length - 2] == c) continue
                out.append(c)
            }
            return out.take(6).toString()
        }
    }
}

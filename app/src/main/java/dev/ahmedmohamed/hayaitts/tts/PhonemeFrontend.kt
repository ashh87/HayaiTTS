package dev.ahmedmohamed.hayaitts.tts

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import java.io.File

/**
 * sherpa-onnx 2.x no longer bundles espeak-ng, so voices that relied on it
 * (Piper, espeak VITS/Matcha, Kitten, Kokoro) are phonemized here and handed
 * to sherpa as `GenerationConfig.phonemeCodepoints`, one array per sentence.
 */
fun interface PhonemeFrontend {
    fun phonemize(text: String): Array<IntArray>
}

/** Process-wide espeak-ng via piper-phonemize. espeak-ng is not thread-safe. */
object Espeak {
    private var initialized = false

    @Synchronized
    fun phonemize(context: Context, text: String, voice: String): List<String> {
        if (!initialized) {
            // Copies the AAR's espeak-ng-data assets to filesDir on first run.
            check(piperPhonemizeInitialize(context) == 0) { "espeak-ng initialisation failed" }
            initialized = true
        }
        val result = piperPhonemizeText(text, voice) ?: error("espeak-ng could not phonemize for voice '$voice'")
        return try {
            result.getAllPhonemesAsStrings()
        } finally {
            result.close()
        }
    }
}

/**
 * Kokoro English: lexicon first (Kokoro was trained on misaki's dictionary
 * phonemes, and custom words live there), espeak for the gaps — the same
 * strategy misaki itself uses. Runs of out-of-lexicon words go to espeak
 * together so numbers and context-dependent words keep their context.
 */
class LexiconFirstFrontend(
    private val lexicon: Map<String, String>,
    private val espeak: (String) -> List<String>,
) : PhonemeFrontend {

    override fun phonemize(text: String): Array<IntArray> =
        SENTENCE_END.split(text)
            .map { sentence -> phonemizeSentence(sentence).codePoints().toArray() }
            .filter { it.isNotEmpty() }
            .toTypedArray()

    internal fun phonemizeSentence(sentence: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in WORD.findAll(sentence)) {
            val phonemes = lexicon[m.value.lowercase().replace('’', '\'')] ?: continue
            appendGap(out, sentence.substring(last, m.range.first))
            out.append(phonemes)
            last = m.range.last + 1
        }
        appendGap(out, sentence.substring(last))
        return out.toString().replace(SPACES, " ").trim()
    }

    // A gap is everything between two lexicon words: spaces, punctuation and
    // any out-of-lexicon words.
    private fun appendGap(out: StringBuilder, gap: String) {
        val start = gap.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) {
            gap.forEach { c -> out.append(if (c.isWhitespace()) ' ' else c) }
            return
        }
        val end = gap.indexOfLast { it.isLetterOrDigit() } + 1
        appendGap(out, gap.substring(0, start))
        out.append(espeak(gap.substring(start, end)).joinToString(" "))
        appendGap(out, gap.substring(end))
    }

    companion object {
        private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")
        private val SPACES = Regex("\\s+")

        /** sherpa-onnx lexicon format: `word p h o n e s`, one token per field. */
        fun load(file: File): Map<String, String> {
            val map = HashMap<String, String>(250_000)
            file.forEachLine { line ->
                val fields = line.trim().split(' ')
                if (fields.size > 1) map.putIfAbsent(fields[0].lowercase(), fields.drop(1).joinToString(""))
            }
            return map
        }
    }
}

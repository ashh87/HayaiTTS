package dev.ahmedmohamed.hayaitts.tts

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import dev.ahmedmohamed.hayaitts.tts.g2p.MisakiEnglish

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
 * Kokoro English: misaki (Kokoro's own G2P), ported in [MisakiEnglish]. Like
 * KPipeline it phonemizes each paragraph whole, then cuts the phonemes into
 * chunks at sentence ends — sherpa hard-splits anything over 400 tokens.
 */
class MisakiFrontend(private val g2p: MisakiEnglish) : PhonemeFrontend {
    override fun phonemize(text: String): Array<IntArray> =
        text.split(PARAGRAPHS)
            .flatMap { chunks(g2p.phonemize(it)) }
            .filter { it.isNotBlank() }
            .map { it.codePoints().toArray() }
            .toTypedArray()

    companion object {
        private const val MAX_CHUNK = 400
        private val PARAGRAPHS = Regex("\n+")
        private val SENTENCE_END = Regex("(?<=[.!?…]) +")

        // KPipeline.waterfall_last, simplified: prefer the strongest punctuation before the limit.
        internal fun chunks(ps: String): List<String> = ps.split(SENTENCE_END).flatMap { sentence ->
            val out = mutableListOf<String>()
            var rest = sentence.trim()
            while (rest.length > MAX_CHUNK) {
                val head = rest.substring(0, MAX_CHUNK)
                val cut = listOf("!.?…", ":;", ",—", " ").firstNotNullOfOrNull { marks ->
                    head.indexOfLast { it in marks }.takeIf { it > 0 }
                } ?: (MAX_CHUNK - 1)
                out += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1).trim()
            }
            out + rest
        }
    }
}

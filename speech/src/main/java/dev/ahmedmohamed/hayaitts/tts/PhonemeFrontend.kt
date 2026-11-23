package dev.ahmedmohamed.hayaitts.tts

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import dev.ahmedmohamed.hayaitts.tts.g2p.MisakiEnglish
import dev.ahmedmohamed.hayaitts.tts.markup.Markup

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
            // Copies the AAR's espeak-ng-data assets to filesDir on first run. The native side runs
            // espeak_Initialize once and returns its sample rate (22050), -1 on failure, then 0 on
            // every later call: so the first success is not 0 (read as failure until 2026-11-23).
            check(piperPhonemizeInitialize(context) >= 0) { "espeak-ng initialisation failed" }
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
        carryMarkers(
            text.split(PARAGRAPHS)
                .flatMap { chunks(g2p.phonemize(it)) }
                .filter { c -> c.any { it != ' ' && it !in Markup.MARKERS } },
        )
            .map { it.codePoints().toArray() }
            .toTypedArray()

    companion object {
        private const val MAX_CHUNK = 400

        /** Starts each chunk with the emphasis/level state earlier chunks left open (expressive.py's carry_markers). */
        internal fun carryMarkers(chunks: List<String>): List<String> {
            var emph = 0
            var level = 0
            return chunks.map { c ->
                val prefix = Markup.EMPH_ON.toString().repeat(emph) +
                    (if (level > 0) Markup.LEVEL_UP else Markup.LEVEL_DOWN).toString().repeat(kotlin.math.abs(level))
                emph = (emph + c.count { it == Markup.EMPH_ON } - c.count { it == Markup.EMPH_OFF }).coerceIn(0, 2)
                level += c.count { it == Markup.LEVEL_UP } - c.count { it == Markup.LEVEL_DOWN }
                prefix + c
            }
        }
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

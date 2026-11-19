package dev.ahmedmohamed.hayaitts.tts.g2p

import android.content.Context
import java.io.File

/**
 * Loads misaki's data from assets/g2p once per process (~1 s on a desktop JVM, ~75 MB
 * of heap), only when a Kokoro English voice is first used.
 */
object MisakiAssets {
    private class Accent(val golds: Map<String, Any>, val silvers: Map<String, Any>)

    private var tokenizer: EnglishTokenizer? = null
    private var tagger: PosTagger? = null
    private val accents = HashMap<Boolean, Accent>()

    @Synchronized
    fun english(context: Context, british: Boolean, customWords: File, espeak: (String) -> String?): MisakiEnglish {
        fun dictionary(name: String) = context.assets.open("g2p/$name").bufferedReader().use { MisakiEnglish.loadDictionary(it.lineSequence()) }
        val tok = tokenizer ?: EnglishTokenizer(context.assets.open("g2p/en_tokenizer.json").bufferedReader().readText()).also { tokenizer = it }
        val tag = tagger ?: context.assets.open("g2p/pos_tagger.txt").bufferedReader().use { PosTagger(it.lineSequence()) }.also { tagger = it }
        val prefix = if (british) "gb" else "us"
        val accent = accents.getOrPut(british) {
            Accent(
                dictionary("${prefix}_gold.tsv"),
                dictionary("${prefix}_silver.tsv"),
            )
        }
        return MisakiEnglish(
            british,
            MisakiEnglish.Dict(accent.golds, loadCustomWords(customWords)),
            MisakiEnglish.Dict(accent.silvers),
            tok,
            tag,
            espeak,
        )
    }

    /** Like the desktop add_custom_words(): golds[word] = golds[word.lower()] = phonemes. */
    internal fun loadCustomWords(file: File): Map<String, Any> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, Any>()
        file.forEachLine { raw ->
            val parts = raw.substringBefore('#').trim().split(Regex("\\s+"))
            if (parts.size == 2) {
                out[parts[0]] = parts[1]
                out[parts[0].lowercase()] = parts[1]
            }
        }
        return out
    }
}

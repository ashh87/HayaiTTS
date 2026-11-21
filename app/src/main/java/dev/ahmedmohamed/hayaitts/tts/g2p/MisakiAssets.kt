package dev.ahmedmohamed.hayaitts.tts.g2p

import android.content.Context
import java.io.File

/**
 * misaki's data from assets/g2p, opened once per process. The dictionaries and
 * tagger weights are memory-mapped [PackedTable]s (no heap, no parsing); only
 * the tokenizer's regexes are compiled at load.
 */
object MisakiAssets {
    private class Accent(val golds: PackedTable, val silvers: PackedTable)

    private var tokenizer: EnglishTokenizer? = null
    private var tagger: PosTagger? = null
    private val accents = HashMap<Boolean, Accent>()

    @Synchronized
    fun english(context: Context, british: Boolean, customWords: File, accentRules: File? = null, espeak: (String) -> String?): MisakiEnglish {
        val (tok, tag, accent) = shared(context, british)
        return MisakiEnglish(
            british,
            MisakiEnglish.Dict(accent.golds, loadCustomWords(customWords)),
            MisakiEnglish.Dict(accent.silvers),
            tok,
            tag,
            espeak,
        ).apply {
            if (british && accentRules?.isFile == true) {
                val table = lexsets ?: PackedTable.asset(context, "g2p/accent_lexsets.kv").also { lexsets = it }
                this.accent = AccentRules(accentRules.readText(), table)
            }
        }
    }

    private var lexsets: PackedTable? = null

    /** Opens everything and pages it in, so the first sentence does not wait on it. */
    @Synchronized
    fun preload(context: Context, british: Boolean) {
        val (_, _, accent) = shared(context, british)
        accent.golds.preload()
        accent.silvers.preload()
        tagTable?.preload()
    }

    private var tagTable: PackedTable? = null

    private fun shared(context: Context, british: Boolean): Triple<EnglishTokenizer, PosTagger, Accent> {
        fun table(name: String) = PackedTable.asset(context, "g2p/$name.kv")
        val tok = tokenizer ?: EnglishTokenizer(context.assets.open("g2p/en_tokenizer.json").bufferedReader().use { it.readText() }).also { tokenizer = it }
        val tag = tagger ?: PosTagger(table("pos_tagger").also { tagTable = it }).also { tagger = it }
        val prefix = if (british) "gb" else "us"
        val accent = accents.getOrPut(british) { Accent(table("${prefix}_gold"), table("${prefix}_silver")) }
        return Triple(tok, tag, accent)
    }

    /**
     * Like the desktop add_custom_words(): word, lowercase and Capitalised forms all map to
     * the phonemes (misaki only grows its own dictionaries that way, at load time). A word in
     * capitals (POI) is an acronym: capitals only, so "poi" stays a word; plurals (POIs) come from misaki's -s.
     */
    internal fun loadCustomWords(file: File): Map<String, Any> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, Any>()
        file.forEachLine { raw ->
            val parts = raw.substringBefore('#').trim().split(Regex("\\s+"))
            if (parts.size == 2) {
                val w = parts[0]
                out[w] = parts[1]
                if (w.length < 2 || w != w.uppercase() || w == w.lowercase()) {
                    val lower = w.lowercase()
                    out[lower] = parts[1]
                    out[lower.replaceFirstChar { it.titlecase() }] = parts[1]
                }
            }
        }
        return out
    }
}

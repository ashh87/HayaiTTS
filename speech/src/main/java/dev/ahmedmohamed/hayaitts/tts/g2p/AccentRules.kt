package dev.ahmedmohamed.hayaitts.tts.g2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Regional accent rules on top of misaki's British phonemes: a port of the kokoro tools' accent.py
 * (kept identical; golden cases in accent_cases.json). [json] is an accents/<name>.json rule set,
 * shipped in the voice bundle as accent.json; [lexsets] is assets/g2p/accent_lexsets.kv
 * (accent.py --kv): words with B (BATH vowel) and R (rhotic r) marks in their GB phonemes.
 *
 * Each word's rules see its phonemes with those marks, then "#" and what follows:
 * "#." a sentence end, "#," other punctuation, "#k" the next word's first phoneme.
 */
class AccentRules(json: String, private val lexsets: PackedTable?) {
    private val rules: List<Pair<Regex, String>>
    private val words: Map<String, String>

    init {
        val o = Json.parseToJsonElement(json).jsonObject
        rules = o["rules"]?.jsonArray?.map { r ->
            val a = r.jsonArray
            Regex(a[0].jsonPrimitive.content) to a[1].jsonPrimitive.content
        } ?: emptyList()
        words = o["words"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    }

    /** Rewrites [ps] (one entry per token, misaki's final phonemes) in place. */
    internal fun apply(tokens: List<MisakiEnglish.MToken>, ps: MutableList<String>) {
        for (i in tokens.indices) {
            val tk = tokens[i]
            if (ps[i].isEmpty() || tk.text.firstOrNull()?.isLetterOrDigit() != true || tk.rating == 5) continue
            val n = (i + 1 until tokens.size).firstOrNull { ps[it].isNotEmpty() }
            val ctx = context(n?.let { tokens[it].text }, n?.let { ps[it] })
            val w = tk.text.lowercase()
            val out = words[w] ?: run {
                var s = marked(tk.text, ps[i]) + ctx
                for ((re, rep) in rules) s = re.replace(s, rep)
                s.substringBefore('#')
            }
            ps[i] = out.filterNot { it in MARKS }
        }
    }

    private fun context(text: String?, ps0: String?): String {
        if (text == null || ps0 == null) return "#."
        val ps = ps0.trimStart { it in STRESS }
        if (text.isBlank() || ps.isEmpty()) return "#."
        if (text.all { it in SENTENCE_END }) return "#."
        if (!text[0].isLetterOrDigit()) return "#,"
        return "#" + ps[0]
    }

    private fun marked(word: String, ps: String): String {
        val t = lexsets ?: return ps
        val w = word.lowercase().replace('’', '\'')
        val stems = listOf(w) + SUFFIXES.filter { w.endsWith(it) && w.length > it.length + 1 }.map { w.dropLast(it.length) }
        for (s in stems) {
            val ann = t.string(s) ?: continue
            transplant(ann, ps)?.let { return it }
        }
        return ps
    }

    companion object {
        private const val MARKS = "BR"
        private const val STRESS = "ˈˌ"
        private const val SENTENCE_END = ".!?"
        private val SUFFIXES = listOf("'s", "s", "es", "ed", "d", "ing")

        /** [ann]'s marks put into [ps] when ps starts with ann's sounds (stress may differ), else null. */
        internal fun transplant(ann: String, ps: String): String? {
            val out = StringBuilder()
            var j = 0
            for (ch in ann) {
                if (ch in STRESS) continue
                if (ch == 'R') { out.append(ch); continue }
                while (j < ps.length && ps[j] in STRESS) out.append(ps[j++])
                if (ch == 'B') { out.append(ch); continue }
                if (j >= ps.length || ps[j] != ch) return null
                out.append(ps[j++])
            }
            return out.append(ps, j, ps.length).toString()
        }
    }
}

package dev.ahmedmohamed.hayaitts.tts.g2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.regex.Pattern

/**
 * spaCy's English tokenizer (the one misaki runs), using spaCy's own prefix /
 * suffix / infix / URL regexes and special cases exported to
 * assets/g2p/en_tokenizer.json by tools/g2p/build_assets.py. Agrees with
 * spaCy on ~99.9% of sentences.
 */
class EnglishTokenizer(json: String) {

    data class Token(val text: String, val whitespace: String)

    private val prefix: Pattern
    private val suffix: Pattern
    private val infix: Pattern
    private val url: Pattern
    private val specials: Map<String, List<String>>

    init {
        val o = Json.parseToJsonElement(json).jsonObject
        fun re(key: String) = unicodePattern(o.getValue(key).jsonPrimitive.content)
        prefix = re("prefix")
        suffix = re("suffix")
        infix = re("infix")
        url = re("url")
        specials = o.getValue("specials").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
    }

    fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        for (word in text.split(WHITESPACE)) {
            if (word.isEmpty()) continue
            val pieces = chunk(word)
            pieces.forEachIndexed { i, p -> out += Token(p, if (i == pieces.lastIndex) " " else "") }
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(whitespace = "")
        return out
    }

    private fun chunk(word: String): List<String> {
        specials[word]?.let { return it }
        val prefixes = mutableListOf<String>()
        val suffixes = mutableListOf<String>()
        val core = splitAffixes(word, prefixes, suffixes)
        val out = mutableListOf<String>()
        prefixes.forEach { out += specials[it] ?: listOf(it) }
        if (core.isNotEmpty()) {
            val special = specials[core]
            when {
                special != null -> out += special
                url.matcher(core).lookingAt() -> out += core
                else -> {
                    var start = 0
                    val m = infix.matcher(core)
                    while (m.find()) {
                        if (m.start() == 0) continue
                        if (m.start() != start) out += core.substring(start, m.start())
                        if (m.start() != m.end()) out += core.substring(m.start(), m.end())
                        start = m.end()
                    }
                    if (start < core.length) out += core.substring(start)
                }
            }
        }
        suffixes.asReversed().forEach { out += specials[it] ?: listOf(it) }
        return out
    }

    // spaCy's Tokenizer._split_affixes
    private fun splitAffixes(word: String, prefixes: MutableList<String>, suffixes: MutableList<String>): String {
        var s = word
        var lastSize = -1
        while (s.isNotEmpty() && s.length != lastSize) {
            if (s in specials) break
            lastSize = s.length
            val pm = prefix.matcher(s)
            val preLen = if (pm.find()) pm.end() else 0
            if (preLen != 0) {
                val minusPre = s.substring(preLen)
                if (minusPre.isNotEmpty() && minusPre in specials) {
                    prefixes += s.substring(0, preLen)
                    s = minusPre
                    break
                }
            }
            val sm = suffix.matcher(s.substring(preLen))
            val sufLen = if (sm.find()) sm.end() - sm.start() else 0
            if (sufLen != 0) {
                val minusSuf = s.substring(0, s.length - sufLen)
                if (minusSuf.isNotEmpty() && minusSuf in specials) {
                    suffixes += s.substring(s.length - sufLen)
                    s = minusSuf
                    break
                }
            }
            if (preLen != 0 && sufLen != 0 && preLen + sufLen <= s.length) {
                prefixes += s.substring(0, preLen)
                suffixes += s.substring(s.length - sufLen)
                s = s.substring(preLen, s.length - sufLen)
            } else if (preLen != 0) {
                prefixes += s.substring(0, preLen)
                s = s.substring(preLen)
            } else if (sufLen != 0) {
                suffixes += s.substring(s.length - sufLen)
                s = s.substring(0, s.length - sufLen)
            }
            if (s.isNotEmpty() && s in specials) break
        }
        return s
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Python-style Unicode \\d \\w. Android's ICU regex already works that way and rejects the flag. */
        fun unicodePattern(p: String): Pattern = try {
            Pattern.compile(p, Pattern.UNICODE_CHARACTER_CLASS)
        } catch (e: IllegalArgumentException) {
            Pattern.compile(p)
        }
    }
}

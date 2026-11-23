package dev.ahmedmohamed.hayaitts.tts.g2p

import java.text.Normalizer

/**
 * Kotlin port of misaki's English G2P (hexgrad/misaki `en.py` + `espeak.py`,
 * Apache-2.0) as Kokoro's KPipeline runs it: G2P(british, fallback=EspeakFallback,
 * unk=''), Kokoro v1.0 phoneme set (ɾ→T, ʔ→t). Function names follow the Python
 * so the two can be read side by side. misaki's markdown links are honoured:
 * `[word](/ipa/)` sets phonemes, `[word](+1)` (or -1, 0.5, …) sets stress.
 *
 * Dictionary values are a phoneme String or a Map of POS tag → phonemes (null = none).
 * [espeak] returns espeak-ng IPA for a word (piper-phonemize style, no tie bars), or
 * null when espeak is unavailable.
 */
class MisakiEnglish(
    private val british: Boolean,
    private val golds: Dict,
    private val silvers: Dict,
    private val tokenizer: EnglishTokenizer,
    private val tagger: PosTagger,
    private val espeak: ((String) -> String?)?,
) {
    /** Regional accent rules applied to the final British phonemes (null: plain misaki). */
    var accent: AccentRules? = null

    /** British number reading (britishNumbers, "hundred and"); off only to compare with stock misaki. */
    internal var ukNumbers = british


    /** Lookup over misaki's dictionary plus per-voice overrides (custom words win). */
    class Dict(private val base: PackedTable?, private val overrides: Map<String, Any> = emptyMap()) {
        operator fun get(k: String): Any? = overrides[k] ?: base?.string(k)?.let(::parseValue)
        operator fun contains(k: String): Boolean = k in overrides || (base != null && k in base)
    }

    class MToken(
        var text: String,
        var tag: String,
        var whitespace: String,
        var phonemes: String? = null,
        var isHead: Boolean = true,
        var alias: String? = null,
        var currency: String? = null,
        var prespace: Boolean = false,
        var rating: Int? = null,
        var stress: Double? = null,
    )

    private data class Ctx(val futureVowel: Boolean? = null, val futureTo: Boolean = false)

    private class Result(val ps: String?, val rating: Int?)

    private val none = Result(null, null)

    /** Phonemes for [text], joined the way KPipeline.en_tokenize joins them. */
    fun phonemize(text: String): String {
        // capital sharp s (street signs): NFKC keeps it and the tokenizer splits on it; -straße split off (Hauptstraße)
        val text = STRASSE.replace(text.replace('ẞ', 'ß'), "$1 $2")
        val pre = withoutMarkers(preprocess(if (ukNumbers) britishNumbers(text) else text))
        val toks = tokenizer.tokenize(pre.text)
        return if (toks.isEmpty()) "" else phonemize(toks, tagger.tag(toks.map { it.text }), pre)
    }

    /**
     * G2P.preprocess: the text without link markup, its whitespace-split words, and link features
     * by word index; [marks] are span markers (Markup.MARKERS) taken out of the text, by offset.
     */
    internal class Preprocessed(
        val text: String,
        val words: List<String>,
        val features: Map<Int, Any>,
        val marks: List<Pair<Int, Char>> = emptyList(),
    )

    /** Tokens and POS tags supplied by the caller (tests feed spaCy's own here). */
    internal fun phonemize(toks: List<EnglishTokenizer.Token>, tags: List<String>, pre: Preprocessed? = null): String {
        val tokens = toks.mapIndexed { i, t -> MToken(t.text, tags[i], t.whitespace) }
        if (pre != null && pre.features.isNotEmpty()) applyFeatures(tokens, pre)
        val words = retokenize(tokens)
        var ctx = Ctx()
        for (w in words.asReversed()) {
            if (w is MToken) {
                if (w.phonemes == null) lexicon(w, ctx).let { w.phonemes = it.ps; w.rating = it.rating }
                if (w.phonemes == null && espeak != null) fallback(w).let { w.phonemes = it.ps; w.rating = it.rating }
                ctx = tokenContext(ctx, w.phonemes, w)
                continue
            }
            @Suppress("UNCHECKED_CAST")
            w as MutableList<MToken>
            var left = 0
            var right = w.size
            var shouldFallback = false
            while (left < right) {
                val span = w.subList(left, right)
                val tk = if (span.any { it.alias != null || it.phonemes != null }) null else mergeTokens(span)
                val r = if (tk == null) none else lexicon(tk, ctx)
                if (r.ps != null) {
                    w[left].phonemes = r.ps
                    w[left].rating = r.rating
                    for (x in w.subList(left + 1, right)) {
                        x.phonemes = ""
                        x.rating = r.rating
                    }
                    ctx = tokenContext(ctx, r.ps, tk!!)
                    right = left
                    left = 0
                } else if (left + 1 < right) {
                    left += 1
                } else {
                    right -= 1
                    val t = w[right]
                    if (t.phonemes == null) {
                        if (t.text.all { it in SUBTOKEN_JUNKS }) {
                            t.phonemes = ""
                            t.rating = 3
                        } else if (espeak != null) {
                            shouldFallback = true
                            break
                        }
                    }
                    left = 0
                }
            }
            if (shouldFallback) {
                fallback(mergeTokens(w)).let { w[0].phonemes = it.ps; w[0].rating = it.rating }
                for (j in 1 until w.size) {
                    w[j].phonemes = ""
                    w[j].rating = w[0].rating
                }
            } else {
                resolveTokens(w)
            }
        }
        @Suppress("UNCHECKED_CAST")
        val merged = words.map { if (it is MToken) it else mergeTokens(it as List<MToken>, unk = "") }
        val ps = merged.map { (it.phonemes ?: "").replace('ɾ', 'T').replace('ʔ', 't') }.toMutableList()
        vocatives(merged, ps)
        accent?.apply(merged, ps)
        if (pre != null && pre.marks.isNotEmpty() && merged.isNotEmpty()) attachMarkers(merged, ps, pre.marks)
        // Kokoro v1.0 (version != '2.0') + KPipeline.en_tokenize: phonemes + one space per whitespace.
        return merged.indices.joinToString("") { i -> ps[i] + if (merged[i].whitespace.isNotEmpty()) " " else "" }.trim()
    }

    /**
     * Terms of address closing a sentence after a comma ("Cheers, mate."): unstressed, after a longer
     * comma (commas in a row). The kokoro tools' expressive.vocatives, kept identical.
     */
    private fun vocatives(tokens: List<MToken>, ps: MutableList<String>) {
        for (i in 1 until tokens.size) {
            val n = VOCATIVES[tokens[i].text.lowercase()] ?: continue
            if (tokens[i - 1].text != "," || tokens[i].rating == 5 || ps[i].isEmpty()) continue
            val next = (i + 1 until tokens.size).firstOrNull { ps[it].isNotEmpty() }
            if (next == null || tokens[next].text.all { it in ".!?" }) {
                ps[i] = ps[i].filterNot { it == 'ˈ' || it == 'ˌ' }
                ps[i - 1] = ",".repeat(n)
            }
        }
    }

    /**
     * G2P.tokenize's feature step: spaCy's Alignment maps each tokenizer token to the
     * preprocessed word covering its characters (whitespace ignored), and the word's link
     * feature lands on it. Phonemes go on the first token of the word, the rest get "".
     */
    private fun applyFeatures(tokens: List<MToken>, pre: Preprocessed) {
        val wordAt = IntArray(pre.words.sumOf { w -> w.count { !it.isWhitespace() } })
        var at = 0
        for ((k, w) in pre.words.withIndex()) for (c in w) if (!c.isWhitespace()) wordAt[at++] = k
        at = 0
        val seen = HashMap<Int, Int>()
        for (tk in tokens) {
            val n = tk.text.count { !it.isWhitespace() }
            if (n == 0 || at >= wordAt.size) continue
            val k = wordAt[at]
            at += n
            val f = pre.features[k] ?: continue
            val i = seen.merge(k, 1, Int::plus)!! - 1
            when {
                f is Double -> tk.stress = f
                f is String && f.startsWith("/") -> {
                    tk.isHead = i == 0
                    tk.phonemes = if (i == 0) f.substring(1) else ""
                    tk.rating = 5
                }
                // shortcut: "#flags#" number features are parsed but not applied; add num_flags if a voice needs them.
            }
        }
    }

    /**
     * Markers back onto the phonemes (expressive.py's _attach): openers and levels go before the
     * next token starting at or after their offset, EMPH_OFF after the last token ending at or before it.
     */
    private fun attachMarkers(tokens: List<MToken>, ps: MutableList<String>, marks: List<Pair<Int, Char>>) {
        val starts = IntArray(tokens.size)
        val ends = IntArray(tokens.size)
        var pos = 0
        for ((i, tk) in tokens.withIndex()) {
            starts[i] = pos
            ends[i] = pos + tk.text.length
            pos = ends[i] + tk.whitespace.length
        }
        for ((o, m) in marks) {
            if (m == EMPH_OFF) {
                val i = (tokens.indices.lastOrNull { ends[it] <= o }) ?: 0
                ps[i] = ps[i] + m
            } else {
                val i = tokens.indices.firstOrNull { starts[it] >= o } ?: tokens.lastIndex
                ps[i] = m + ps[i]
            }
        }
    }

    // ---- G2P helpers -------------------------------------------------------

    private fun mergeTokens(tokens: List<MToken>, unk: String? = null): MToken {
        val currency = tokens.mapNotNull { it.currency }.toSet()
        val stresses = tokens.mapNotNull { it.stress }.toSet()
        val ratings = tokens.map { it.rating }
        val phonemes = if (unk == null) null else buildString {
            for (tk in tokens) {
                if (tk.prespace && isNotEmpty() && !last().isWhitespace() && !tk.phonemes.isNullOrEmpty()) append(' ')
                append(tk.phonemes ?: unk)
            }
        }
        return MToken(
            text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text,
            tag = tokens.maxBy { t -> t.text.sumOf { c -> if (c == c.lowercaseChar()) 1 else 2 as Int } }.tag,
            whitespace = tokens.last().whitespace,
            phonemes = phonemes,
            isHead = tokens.first().isHead,
            currency = currency.maxOrNull(),
            prespace = tokens.first().prespace,
            rating = if (null in ratings) null else ratings.filterNotNull().min(),
            stress = stresses.singleOrNull(),
        )
    }

    private fun retokenize(tokens: List<MToken>): List<Any> {
        val words = mutableListOf<Any>()
        var currency: String? = null
        for ((i, token) in tokens.withIndex()) {
            val tks = if (token.alias == null && token.phonemes == null) {
                subtokenize(token.text).map { MToken(it, token.tag, "", stress = token.stress) }
            } else {
                listOf(token)
            }
            if (tks.isEmpty()) continue
            tks.last().whitespace = token.whitespace
            for ((j, tk) in tks.withIndex()) {
                if (tk.alias != null || tk.phonemes != null) {
                    // keep
                } else if (tk.tag == "$" && tk.text in CURRENCIES) {
                    currency = tk.text
                    tk.phonemes = ""
                    tk.rating = 4
                } else if (tk.tag == ":" && (tk.text == "-" || tk.text == "–")) {
                    tk.phonemes = "—"
                    tk.rating = 3
                } else if (tk.tag in PUNCT_TAGS && !tk.text.all { it.lowercaseChar() in 'a'..'z' }) {
                    tk.phonemes = PUNCT_TAG_PHONEMES[tk.tag] ?: tk.text.filter { it in PUNCTS }
                    tk.rating = 4
                } else if (currency != null) {
                    if (tk.tag != "CD") {
                        currency = null
                    } else if (j + 1 == tks.size && (i + 1 == tokens.size || tokens[i + 1].tag != "CD")) {
                        tk.currency = currency
                    }
                } else if (j > 0 && j < tks.size - 1 && tk.text == "2" &&
                    "${tks[j - 1].text.last()}${tks[j + 1].text.first()}".all { it.isLetter() }
                ) {
                    tk.alias = "to"
                }
                val last = words.lastOrNull()
                if (tk.alias != null || tk.phonemes != null) {
                    words += tk
                } else if (last is MutableList<*> && (last.last() as MToken).whitespace.isEmpty()) {
                    tk.isHead = false
                    @Suppress("UNCHECKED_CAST")
                    (last as MutableList<MToken>) += tk
                } else {
                    words += if (tk.whitespace.isNotEmpty()) tk else mutableListOf(tk)
                }
            }
        }
        return words.map { if (it is MutableList<*> && it.size == 1) it[0]!! else it }
    }

    private fun tokenContext(ctx: Ctx, ps: String?, token: MToken): Ctx {
        var vowel = ctx.futureVowel
        if (!ps.isNullOrEmpty()) {
            for (c in ps) {
                if (c in VOWELS || c in CONSONANTS || c in NON_QUOTE_PUNCTS) {
                    vowel = if (c in NON_QUOTE_PUNCTS) null else c in VOWELS
                    break
                }
            }
        }
        val futureTo = token.text == "to" || token.text == "To" || (token.text == "TO" && (token.tag == "TO" || token.tag == "IN"))
        return Ctx(vowel, futureTo)
    }

    private fun resolveTokens(tokens: List<MToken>) {
        val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
        val prespace = ' ' in text || '/' in text ||
            text.filter { it !in SUBTOKEN_JUNKS }.map { if (it.isLetter()) 0 else if (it in '0'..'9') 1 else 2 }.toSet().size > 1
        for ((i, tk) in tokens.withIndex()) {
            if (tk.phonemes == null) {
                if (i == tokens.lastIndex && tk.text.length == 1 && tk.text[0] in NON_QUOTE_PUNCTS) {
                    tk.phonemes = tk.text
                    tk.rating = 3
                } else if (tk.text.all { it in SUBTOKEN_JUNKS }) {
                    tk.phonemes = ""
                    tk.rating = 3
                }
            } else if (i > 0) {
                tk.prespace = prespace
            }
        }
        if (prespace) return
        data class Ix(val primary: Boolean, val weight: Int, val i: Int)
        var indices = tokens.withIndex().filter { !it.value.phonemes.isNullOrEmpty() }
            .map { (i, tk) -> Ix(PRIMARY in tk.phonemes!!, stressWeight(tk.phonemes!!), i) }
        if (indices.size == 2 && tokens[indices[0].i].text.length == 1) {
            val i = indices[1].i
            tokens[i].phonemes = applyStress(tokens[i].phonemes, -0.5)
            return
        } else if (indices.size < 2 || indices.count { it.primary } <= (indices.size + 1) / 2) {
            return
        }
        indices = indices.sortedWith(compareBy<Ix>({ it.primary }, { it.weight }, { it.i })).take(indices.size / 2)
        for (ix in indices) tokens[ix.i].phonemes = applyStress(tokens[ix.i].phonemes, -0.5)
    }

    // ---- Lexicon -------------------------------------------------------------

    private fun lexicon(tk: MToken, ctx: Ctx): Result {
        var word = (tk.alias ?: tk.text).replace('‘', '\'').replace('’', '\'')
        word = Normalizer.normalize(word, Normalizer.Form.NFKC)
        word = word.map { c -> if (c.isDigit() && c !in '0'..'9' && Character.getNumericValue(c) >= 0) Character.getNumericValue(c).toString() else c.toString() }.joinToString("")
        val stress = if (word == word.lowercase()) null else if (word == word.uppercase()) 2.0 else 0.5
        val r = getWord(word, tk.tag, stress, ctx)
        if (r.ps != null) return Result(applyStress(appendCurrency(r.ps, tk.currency), tk.stress), r.rating)
        if (isNumber(word, tk.isHead)) return getNumber(word, tk.currency, tk.isHead).let { Result(applyStress(it.ps, tk.stress), it.rating) }
        return none
    }

    private fun getNNP(word: String): Result {
        val ps = word.filter { it.isLetter() }.map { golds[it.uppercase()] as? String ?: return none }
        val stressed = applyStress(ps.joinToString(""), 0.0)!!
        val i = stressed.lastIndexOf(SECONDARY)
        return Result(if (i < 0) stressed else stressed.substring(0, i) + PRIMARY + stressed.substring(i + 1), 3)
    }

    private fun getSpecialCase(word: String, tag: String, stress: Double?, ctx: Ctx): Result {
        val core = word.trim('.')
        return when {
            tag == "ADD" && word in ADD_SYMBOLS -> lookup(ADD_SYMBOLS.getValue(word), null, -0.5, ctx)
            word in SYMBOLS -> lookup(SYMBOLS.getValue(word), null, null, ctx)
            '.' in core && word.replace(".", "").let { it.isNotEmpty() && it.all(Char::isLetter) } &&
                word.split('.').maxOf { it.length } < 3 -> getNNP(word)
            word == "a" || word == "A" -> Result(if (tag == "DT") "ɐ" else "ˈA", 4)
            word == "am" || word == "Am" || word == "AM" -> when {
                tag.startsWith("NN") -> getNNP(word)
                ctx.futureVowel == null || word != "am" || (stress != null && stress > 0) -> Result(golds["am"] as? String, 4)
                else -> Result("ɐm", 4)
            }
            word == "an" || word == "An" || word == "AN" ->
                if (word == "AN" && tag.startsWith("NN")) getNNP(word) else Result("ɐn", 4)
            word == "I" && tag == "PRP" -> Result("${SECONDARY}I", 4)
            (word == "by" || word == "By" || word == "BY") && parentTag(tag) == "ADV" -> Result("bˈI", 4)
            word == "to" || word == "To" || (word == "TO" && (tag == "TO" || tag == "IN")) ->
                Result(when (ctx.futureVowel) { null -> golds["to"] as? String; false -> "tə"; true -> "tʊ" }, 4)
            word == "in" || word == "In" || (word == "IN" && tag != "NNP") ->
                Result((if (ctx.futureVowel == null || tag != "IN") PRIMARY.toString() else "") + "ɪn", 4)
            word == "the" || word == "The" || (word == "THE" && tag == "DT") ->
                Result(if (ctx.futureVowel == true) "ði" else "ðə", 4)
            tag == "IN" && VS.matches(word) -> lookup("versus", null, null, ctx)
            word == "used" || word == "Used" || word == "USED" -> {
                val used = golds["used"] as Map<*, *>
                Result((if ((tag == "VBD" || tag == "JJ") && ctx.futureTo) used["VBD"] else used["DEFAULT"]) as String?, 4)
            }
            else -> none
        }
    }

    private fun parentTag(tag: String?): String? = when {
        tag == null -> null
        tag.startsWith("VB") -> "VERB"
        tag.startsWith("NN") -> "NOUN"
        tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
        tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
        else -> tag
    }

    private fun isKnown(word: String): Boolean = when {
        word in golds || word in SYMBOLS || word in silvers -> true
        !word.all { it.isLetter() } || word.isEmpty() || !word.all { it.code in LEXICON_ORDS } -> false
        word.length == 1 -> true
        word == word.uppercase() && word.lowercase() in golds -> true
        else -> word.substring(1) == word.substring(1).uppercase()
    }

    private fun lookup(word0: String, tag0: String?, stress: Double?, ctx: Ctx?): Result {
        var word = word0
        var tag = tag0
        var isNNP: Boolean? = null
        if (word == word.uppercase() && word !in golds) {
            word = word.lowercase()
            isNNP = tag == "NNP"
        }
        var ps: Any? = golds[word]
        var rating = 4
        if (ps == null && isNNP != true) {
            ps = silvers[word]
            rating = 3
        }
        if (ps is Map<*, *>) {
            if (ctx != null && ctx.futureVowel == null && "None" in ps) {
                tag = "None"
            } else if (tag !in ps) {
                tag = parentTag(tag)
            }
            ps = if (ps.containsKey(tag)) ps[tag] else ps["DEFAULT"]
        }
        val s = ps as String?
        if (s == null || (isNNP == true && PRIMARY !in s)) {
            val nnp = getNNP(word)
            if (nnp.ps != null) return nnp
        }
        return Result(applyStress(s, stress), rating)
    }

    private fun suffixS(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "ptkfθ" -> stem + "s"
        stem.last() in "szʃʒʧʤ" -> stem + (if (british) "ɪ" else "ᵻ") + "z"
        else -> stem + "z"
    }

    private fun stemS(word: String, tag: String?, stress: Double?, ctx: Ctx?): Result {
        if (word.length < 3 || !word.endsWith("s")) return none
        val stem = when {
            !word.endsWith("ss") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) &&
                isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return none
        }
        val r = lookup(stem, tag, stress, ctx)
        return Result(suffixS(r.ps), r.rating)
    }

    private fun suffixEd(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "pkfθʃsʧ" -> stem + "t"
        stem.last() == 'd' -> stem + (if (british) "ɪ" else "ᵻ") + "d"
        stem.last() != 't' -> stem + "d"
        british || stem.length < 2 -> stem + "ɪd"
        stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
        else -> stem + "ᵻd"
    }

    private fun stemEd(word: String, tag: String?, stress: Double?, ctx: Ctx?): Result {
        if (word.length < 4 || !word.endsWith("d")) return none
        val stem = when {
            !word.endsWith("dd") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            else -> return none
        }
        val r = lookup(stem, tag, stress, ctx)
        return Result(suffixEd(r.ps), r.rating)
    }

    private fun suffixIng(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        british -> if (stem.last() in "əː") null else stem + "ɪŋ"
        stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
        else -> stem + "ɪŋ"
    }

    private fun stemIng(word: String, tag: String?, stress: Double?, ctx: Ctx?): Result {
        if (word.length < 5 || !word.endsWith("ing")) return none
        val stem = when {
            word.length > 5 && isKnown(word.dropLast(3)) -> word.dropLast(3)
            isKnown(word.dropLast(3) + "e") -> word.dropLast(3) + "e"
            word.length > 5 && DOUBLED_ING.containsMatchIn(word) && isKnown(word.dropLast(4)) -> word.dropLast(4)
            else -> return none
        }
        val r = lookup(stem, tag, stress, ctx)
        return Result(suffixIng(r.ps), r.rating)
    }

    private fun getWord(word0: String, tag: String, stress: Double?, ctx: Ctx): Result {
        getSpecialCase(word0, tag, stress, ctx).let { if (it.ps != null) return it }
        var word = word0
        val wl = word.lowercase()
        if (word.length > 1 && word.replace("'", "").let { it.isNotEmpty() && it.all(Char::isLetter) } &&
            word != wl && (tag != "NNP" || word.length > 7) && word !in golds && word !in silvers &&
            (word == word.uppercase() || word.substring(1) == word.substring(1).lowercase()) &&
            (wl in golds || wl in silvers || !stemS(wl, tag, stress, ctx).ps.isNullOrEmpty() ||
                !stemEd(wl, tag, stress, ctx).ps.isNullOrEmpty() || !stemIng(wl, tag, stress, ctx).ps.isNullOrEmpty())
        ) {
            word = wl
        }
        return when {
            isKnown(word) -> lookup(word, tag, stress, ctx)
            word.endsWith("s'") && isKnown(word.dropLast(2) + "'s") -> lookup(word.dropLast(2) + "'s", tag, stress, ctx)
            word.endsWith("'") && isKnown(word.dropLast(1)) -> lookup(word.dropLast(1), tag, stress, ctx)
            else -> stemS(word, tag, stress, ctx).takeIf { it.ps != null }
                ?: stemEd(word, tag, stress, ctx).takeIf { it.ps != null }
                ?: stemIng(word, tag, stress ?: 0.5, ctx).takeIf { it.ps != null }
                ?: none
        }
    }

    private fun isCurrency(word: String): Boolean = when {
        '.' !in word -> true
        word.count { it == '.' } > 1 -> false
        else -> word.split('.')[1].length < 3
    }

    private fun getNumber(word0: String, currency: String?, isHead: Boolean): Result {
        val suffix = NUMBER_SUFFIX.find(word0)?.value
        var word = if (suffix != null) word0.dropLast(suffix.length) else word0
        val result = mutableListOf<Result>()
        if (word.startsWith("-")) {
            result += lookup("minus", null, null, null)
            word = word.substring(1)
        }
        fun extendNum(num: String, escape: Boolean = false) {
            val text = if (escape) num else NumberWords.cardinal(num.toBigInteger())
            for (w in text.split(NON_LETTERS)) {
                if (w != "and") result += lookup(w, null, if (w == "point") -2.0 else null, null)
                // British "and", reduced and joined on (hundred-ən fifty): misaki's "n" number flag, always on
                else if (ukNumbers && result.isNotEmpty()) result[result.size - 1] = result.last().let { Result((it.ps ?: "") + "ən", it.rating) }
            }
        }
        when {
            isDigit(word) && suffix in ORDINALS -> extendNum(NumberWords.ordinal(word.toBigInteger()), escape = true)
            result.isEmpty() && word.length == 4 && (currency == null || currency !in CURRENCIES) && isDigit(word) ->
                extendNum(NumberWords.year(word.toLong()), escape = true)
            !isHead && '.' !in word -> {
                val num = word.replace(",", "")
                when {
                    num.isEmpty() -> {}
                    num[0] == '0' || num.length > 3 -> num.forEach { extendNum(it.toString()) }
                    num.length == 3 && !num.endsWith("00") -> {
                        extendNum(num.substring(0, 1))
                        if (num[1] == '0') {
                            result += lookup("O", null, -2.0, null)
                            extendNum(num.substring(2))
                        } else {
                            extendNum(num.substring(1))
                        }
                    }
                    else -> extendNum(num)
                }
            }
            word.count { it == '.' } > 1 || !isHead -> {
                for (num in word.replace(",", "").split('.')) {
                    when {
                        num.isEmpty() -> {}
                        num[0] == '0' || (num.length != 2 && num.drop(1).any { it != '0' }) -> num.forEach { extendNum(it.toString()) }
                        else -> extendNum(num)
                    }
                }
            }
            currency != null && currency in CURRENCIES && isCurrency(word) -> {
                val units = CURRENCIES.getValue(currency)
                var pairs = word.replace(",", "").split('.').zip(units.toList()).map { (n, u) -> (if (n.isEmpty()) 0L else n.toLong()) to u }
                if (pairs.size > 1) {
                    if (pairs[1].first == 0L) pairs = pairs.take(1) else if (pairs[0].first == 0L) pairs = pairs.drop(1)
                }
                for ((i, p) in pairs.withIndex()) {
                    val (num, unit) = p
                    if (i > 0) result += lookup("and", null, null, null)
                    extendNum(num.toString())
                    result += if (kotlin.math.abs(num) != 1L && unit != "pence") stemS(unit + "s", null, null, null) else lookup(unit, null, null, null)
                }
            }
            else -> {
                val words = when {
                    isDigit(word) -> NumberWords.cardinal(word.toBigInteger())
                    '.' !in word -> word.replace(",", "").toBigInteger().let { if (suffix in ORDINALS) NumberWords.ordinal(it) else NumberWords.cardinal(it) }
                    else -> word.replace(",", "").let { w ->
                        if (w[0] == '.') "point " + w.substring(1).map { NumberWords.cardinal(it.digitToInt().toLong()) }.joinToString(" ")
                        else NumberWords.decimal(w)
                    }
                }
                extendNum(words, escape = true)
            }
        }
        if (result.isEmpty()) return none
        val ps = result.mapNotNull { it.ps }.joinToString(" ")
        val rating = result.mapNotNull { it.rating }.minOrNull()
        return when (suffix) {
            "s", "'s" -> Result(suffixS(ps), rating)
            "ed", "'d" -> Result(suffixEd(ps), rating)
            "ing" -> Result(suffixIng(ps), rating)
            else -> Result(ps, rating)
        }
    }

    private fun appendCurrency(ps: String, currency: String?): String {
        val unit = currency?.let { CURRENCIES[it] } ?: return ps
        val c = stemS(unit.first + "s", null, null, null).ps ?: return ps
        return "$ps $c"
    }

    private fun isNumber(word0: String, isHead: Boolean): Boolean {
        if (word0.none { it in '0'..'9' }) return false
        var word = word0
        for (s in NUMBER_SUFFIXES) {
            if (word.endsWith(s)) {
                word = word.dropLast(s.length)
                break
            }
        }
        return word.withIndex().all { (i, c) -> c in '0'..'9' || c == ',' || c == '.' || (isHead && i == 0 && c == '-') }
    }

    // ---- EspeakFallback --------------------------------------------------------

    private fun fallback(tk: MToken): Result {
        val raw = preservePunctuation(tk.text) { espeak?.invoke(it) } ?: return none
        return Result(espeakToMisaki(raw.trim(), british), 2)
    }

    companion object {
        // Which 4-digit numbers read in pairs (years, flight 1250) and which in full: see britishNumbers
        private val YEAR_BEFORE = setOf(
            "in", "since", "from", "until", "till", "by", "before", "after", "during", "circa", "c.", "of",
            "year", "born", "died", "founded", "built", "established", "est.", "copyright", "©", "vintage",
            "summer", "winter", "spring", "autumn", "fall", "between", "early", "late", "mid",
        )
        // a date (or a statute: Census Act 1920): wins over everything after (17 October 1883 of ...)
        private val MONTHS = setOf(
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov",
            "dec", "day", "act",
        )
        private val IDENT_BEFORE = setOf("flight", "room", "bus", "route", "platform", "gate", "train", "model", "no.", "number")
        private val NUMBER_BEFORE = setOf("cost", "costs", "paid", "pay", "weighs", "scored", "total", "than", "sold", "earned", "raised")
        // a count, unless the number looks like a year (around 1990, things...)
        private val ABOUT_BEFORE = setOf(
            "about", "around", "approximately", "roughly", "nearly", "almost", "over", "under", "exactly", "only", "just", "some",
        )
        private val YEAR_AFTER = setOf("ad", "bc", "bce", "ce", "onwards", "onward")
        private val YEAR_VERBS = setOf("was", "is", "will", "has", "had", "saw", "brought", "marked", "began", "ended")
        private val RANGE = setOf("to", "and", "or", "-", "–")
        private val UNITS = setOf( // a count
            "m", "metre", "metres", "meter", "meters", "km", "kilometre", "kilometres", "kilometer", "kilometers",
            "mi", "mile", "miles", "yd", "yard", "yards", "ft", "foot", "feet", "kg", "g", "mph", "kph", "%",
            "percent", "per", "pounds", "quid", "euros", "dollars", "pence", "times", "years", "months",
            "weeks", "days", "hours", "minutes", "seconds", "hz",
            "khz", "mhz", "ghz", "kw", "mw", "cc", "ml", "litres", "liters", "tonnes", "tons", "acres", "hectares",
        )
        private val PLURALS = setOf("people", "children", "men", "women", "staff", "sheep", "fish", "data", "police", "cattle", "teeth", "mice")
        private val TIME_BEFORE = setOf( // their 2023 reviews
            "in", "at", "by", "since", "from", "until", "till", "before", "after", "during", "between", "the",
            "their", "his", "her", "its", "our", "my", "your", "this", "that", "last", "next",
        )
        private val STRONG_TIME_BEFORE = setOf("by", "since", "until", "till", "before", "after", "during") // by 1674 thousands remained
        private val YEARLIKE = 1800..2099 // with no other cue these read as years ("1990"), others in full ("1250")
        private val NO_TOUCH = setOf("isbn", "issn") // digit strings, left to misaki
        private const val NUM = "[1-9]\\d{3}(?![\\w.,:]?\\w)"
        private val FOUR_DIGITS = Regex("(?<![\\w£$€¥.,:])($NUM)")
        private val RANGE_NEXT = Regex("^\\s*(?:to|and|or|-|–)\\s*$NUM")
        private val TIGHT_RANGE = Regex("^[-–](\\d{2}\\b|$NUM)")
        private val LAST_WORD = Regex("(\\S+)\\s*$")
        private val WORD_BEFORE_LAST = Regex("(\\S+)\\s+\\S+\\s*$")
        private val TOKEN_HEAD = Regex("\\S*$")
        private val TOKEN_TAIL = Regex("^\\S*")
        private val MID = Regex("mid-$", RegexOption.IGNORE_CASE)
        private val HEDGE = Regex("\\s+(?:at\\s+(?:least|most|the\\s+(?:latest|earliest))|as\\s+(?:early|late)\\s+as)\\s*$")
        private val BACK_TO = Regex(
            "\\b(?:(?:dat|trac)(?:e|es|ed|ing)(?:\\s+back)?|(?:go|goes|went|going|gone)\\s+back)\\s+to\\s*$", RegexOption.IGNORE_CASE,
        )
        private val FIRST_WORD = Regex("^\\s*([^\\s.,;:!?)]+|[.,;:!?)])")
        private val CLAUSE_END = Regex("^\\s*([.,;:!?)]|$)")
        private val LIST_GAP = Regex("[,\\s]*(and\\s+|or\\s+)?")
        private val RANGE_GAP = Regex("\\s*(and|to|or|-|–)\\s*")
        private val RANGE_END = Regex("\\d{4}\\s*[-–]\\s*$")
        private val SPACES = Regex("\\s+")

        /** A plural noun, by its spelling ([w] as written: "James" is a name). */
        private fun plural(w: String) = w.lowercase() in PLURALS ||
            (w.isNotEmpty() && w.all { it.isLetter() } && w == w.lowercase() && w.length > 3 && w.endsWith("s") &&
                !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && w != "series" && w != "species")

        private fun possessive(w: String) = w.endsWith("'s") || w.endsWith("’s")

        /**
         * After "in 1990 cases": a count if it goes on as one ("out of 2000", "per year"), or for a number that
         * doesn't look like a year, if the phrase ends there ("in 1250 ways."). Else a year: "in 1990 cases of
         * measles peaked", "by 1674 thousands remained", "the 2015 earthquakes.".
         */
        private fun counted(rest: String, n: Int): Boolean {
            if (CLAUSE_END.containsMatchIn(rest)) return n !in YEARLIKE
            val ws = rest.lowercase().trim().split(SPACES)
            val w = ws[0]
            val w2 = ws.getOrElse(1) { "" }
            return w in setOf("out", "per") || (w == "of" && w2.firstOrNull()?.isDigit() == true)
        }

        /**
         * Misaki reads every 4-digit number in pairs, as a year ("1250 metres" -> "twelve fifty"). Pairs suit
         * years and identifiers; quantities read in full, so they get a comma (1,250). Decided from the words
         * around each number; same as kokoro-tools expressive.british_numbers (cases: number_cases.json).
         */
        internal fun britishNumbers(text: String): String {
            val out = StringBuilder()
            var last = 0
            var year = false
            var lastYearEnd = -1
            for (m in FOUR_DIGITS.findAll(text)) {
                val head = text.substring(0, m.range.first)
                var rest = text.substring(m.range.last + 1)
                val token = TOKEN_HEAD.find(head)!!.value + m.value + TOKEN_TAIL.find(rest)!!.value
                val cue = HEDGE.replace(head, " ") // since at least 1600
                val prev = LAST_WORD.find(cue)?.groupValues?.get(1)?.trim('(', '"', '\'') ?: ""
                val pv = prev.lowercase()
                val pv2 = WORD_BEFORE_LAST.find(cue)?.groupValues?.get(1)?.lowercase() ?: ""
                if (token.count { it == '-' || it == '–' } > 1 || pv in NO_TOUCH || pv2 in NO_TOUCH) {
                    continue // ISBN 978-0-7524-9485-2: left as it is
                }
                val mid = MID.containsMatchIn(head) // mid-1066
                val dash = !mid && (head.trimEnd().endsWith("-") || head.trimEnd().endsWith("–")) // 1990-2010
                val back = BACK_TO.containsMatchIn(head) // dates back to 1080
                RANGE_NEXT.find(rest)?.let { rest = rest.substring(it.range.last + 1) } // 1990 to 2000: the cue is after the range
                val after = FIRST_WORD.find(rest)?.groupValues?.get(1) ?: ""
                val after2 = rest.trim().split(SPACES).getOrElse(1) { "" }
                val n = m.value.toInt()
                val nxt = after.lowercase()
                val start = prev.isEmpty() || prev.last() in ".!?"
                val yearCue = pv in YEAR_BEFORE || mid || back // in 1990
                val timeCue = pv in TIME_BEFORE || back // in / the / their 1990 (before a plural)
                val carry = year && (pv in RANGE || dash) // the 2011 and 2012 races
                // flight 4418; Fire of London 1666, CP 4418 (a capitalised word, not the sentence's first)
                val ident = pv in IDENT_BEFORE || (prev.firstOrNull()?.isUpperCase() == true && prev.all { it.isLetter() } &&
                    pv2.isNotEmpty() && pv2.last() !in ".!?")
                val listed = year && lastYearEnd >= 0 && LIST_GAP.matches(text.substring(lastYearEnd, m.range.first)) // 1051, 1052, and 1065
                val tightM = TIGHT_RANGE.find(text.substring(m.range.last + 1)) // 1990-2010, 1107–37
                val n2 = tightM?.groupValues?.get(1)?.takeIf { it.length == 4 }?.toInt()
                // 2013–2014 games, not 1000–2000 men
                val tight = tightM != null && (n2 == null || (n < n2 && n2 < 2100 && (n % 100 != 0 || n2 % 100 != 0)))
                val ranged = year && lastYearEnd >= 0 && RANGE_GAP.matches(text.substring(lastYearEnd, m.range.first))
                val noun = after.isNotEmpty() && after.all { it.isLetter() } && after == after.lowercase()
                // A decision list, as classify_numbers in the kokoro tools: the first rule that fits decides
                year = when {
                    // certain: dates and ranges
                    dash && RANGE_END.containsMatchIn(head) -> year // the end of 1000–2000: read like its start
                    nxt in YEAR_AFTER || pv in MONTHS || tight || ranged -> true // AD/BC, 7 October 2023, ranges
                    ident && n in YEARLIKE -> true // Paris 2024 uniforms
                    head.endsWith("(") && rest.startsWith(")") -> true // (1693)
                    // strong: a count (unit, cost 1250, out of, 1250 of them, about)
                    (n >= 2100 && !ident) || (nxt in UNITS && after2.firstOrNull()?.isDigit() != true) || pv in NUMBER_BEFORE ||
                        (pv == "of" && pv2 == "out") || (nxt == "of" && !(yearCue && n in YEARLIKE)) ||
                        (pv in ABOUT_BEFORE && (n !in YEARLIKE || plural(after))) -> false
                    // medium: a plural noun after (in 1990 cases out of 2000 | of measles peaked; the 1990 figures showed)
                    plural(after) -> (timeCue || possessive(pv) || carry) &&
                        !counted(rest.trimStart().substring(after.length), n) && (n in YEARLIKE || pv in STRONG_TIME_BEFORE)
                    listed || ident || yearCue || carry -> true
                    // weak
                    after.firstOrNull()?.isUpperCase() == true && !start -> true // the 1990 World Cup
                    (pv == "the" || possessive(pv)) && noun -> true // the 2010 election
                    (pv2 == "the" || possessive(pv2)) && prev.isNotEmpty() && prev.all { it.isLetter() } && prev == pv &&
                        n in YEARLIKE && noun -> true // the classic 1902 series
                    else -> n in YEARLIKE || (start && nxt in YEAR_VERBS) // 1990; 1250 was a bad year
                }
                if (year) lastYearEnd = m.range.last + 1
                out.append(text, last, m.range.first).append(if (year) m.value else m.value[0] + "," + m.value.substring(1))
                last = m.range.last + 1
            }
            return out.append(text, last, text.length).toString()
        }

        // count of commas before each, by ear (3 for love, 2 for mate)
        private val VOCATIVES = mapOf(
            "love" to 3, "mate" to 2, "pet" to 2, "duck" to 2, "darling" to 2, "dear" to 2, "hun" to 2, "babe" to 2,
            "pal" to 2, "hen" to 2, "chuck" to 2, "sweetheart" to 2,
        )
        const val PRIMARY = 'ˈ'
        const val SECONDARY = 'ˌ'
        private const val STRESSES = "ˌˈ"
        private val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
        private val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()
        private val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ".toSet()
        private val DIPHTHONGS = "AIOQWYʤʧ".toSet()
        private val PUNCTS = ";:,.!?—…\"“”".toSet()
        private val NON_QUOTE_PUNCTS = PUNCTS - "\"“”".toSet()
        /** expressive.py's STRASSE: a German street name's -straße/-strasse ending as its own word. */
        private val STRASSE = Regex("(\\p{L}{2,})-?(stra(?:ß|ss)e)\\b", RegexOption.IGNORE_CASE)
        private val SUBTOKEN_JUNKS = "',-._‘’/".toSet()
        private val PUNCT_TAGS = setOf(".", ",", "-LRB-", "-RRB-", "``", "\"\"", "''", ":", "$", "#", "NFP")
        private val PUNCT_TAG_PHONEMES = mapOf("-LRB-" to "(", "-RRB-" to ")", "``" to "“", "\"\"" to "”", "''" to "”")
        private val LEXICON_ORDS = (listOf(39, 45) + (65..90) + (97..122)).toSet()
        private val CURRENCIES = mapOf("$" to ("dollar" to "cent"), "£" to ("pound" to "pence"), "€" to ("euro" to "cent"))
        private val ORDINALS = setOf("st", "nd", "rd", "th")
        private val NUMBER_SUFFIXES = listOf("ing", "'d", "ed", "'s", "st", "nd", "rd", "th", "s")
        private val ADD_SYMBOLS = mapOf("." to "dot", "/" to "slash")
        private val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
        private val VS = Regex("(?i)vs\\.?")
        private val DOUBLED_ING = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")
        private val NUMBER_SUFFIX = Regex("[a-z']+$")
        private val NON_LETTERS = Regex("[^a-z]+")
        private val SUBTOKEN = EnglishTokenizer.unicodePattern(
            "^['‘’]+|\\p{Lu}(?=\\p{Lu}\\p{Ll})|(?:^-)?(?:\\d?[,.]?\\d)+|[-_]+|['‘’]{2,}|" +
                "\\p{L}*?(?:['‘’]\\p{L})*?\\p{Ll}(?=\\p{Lu})|\\p{L}+(?:['‘’]\\p{L})*|[^-_\\p{L}'‘’\\d]|['‘’]+$",
        ).toRegex()

        private fun isDigit(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

        private val LINK = Regex("""\[([^\]]+)\]\(([^)]*)\)""")
        private val WHITESPACE = Regex("""\s+""")

        private const val EMPH_OFF = ''
        private const val MARKERS = "" // = Markup.MARKERS

        /** Takes span markers out of the preprocessed text, remapping link features to the remaining words. */
        internal fun withoutMarkers(pre: Preprocessed): Preprocessed {
            if (pre.text.none { it in MARKERS }) return pre
            val clean = StringBuilder()
            val marks = mutableListOf<Pair<Int, Char>>()
            for (c in pre.text) {
                when {
                    c in MARKERS -> marks += clean.length to c
                    // the tokens don't count a doubled or leading space ("it, \uE002 \uE000right"): offsets mustn't either
                    c == ' ' && (clean.isEmpty() || clean.last() == ' ') -> {}
                    else -> clean.append(c)
                }
            }
            val kept = mutableListOf<String>()
            val remap = HashMap<Int, Int>()
            for ((i, w) in pre.words.withIndex()) {
                val bare = w.filterNot { it in MARKERS }
                if (bare.isNotEmpty()) {
                    remap[i] = kept.size
                    kept += bare
                }
            }
            val features = pre.features.mapNotNull { (k, v) -> remap[k]?.let { it to v } }.toMap()
            return Preprocessed(clean.toString(), kept, features, marks)
        }

        /** misaki G2P.preprocess. */
        internal fun preprocess(text0: String): Preprocessed {
            val text = text0.trimStart()
            val out = StringBuilder()
            val words = mutableListOf<String>()
            val features = HashMap<Int, Any>()
            var last = 0
            for (m in LINK.findAll(text)) {
                val before = text.substring(last, m.range.first)
                out.append(before)
                words += before.split(WHITESPACE).filter { it.isNotEmpty() }
                val f = m.groupValues[2]
                val feature: Any? = when {
                    isDigit(if (f.startsWith("-") || f.startsWith("+")) f.substring(1) else f) -> f.toInt().toDouble()
                    f == "0.5" || f == "+0.5" -> 0.5
                    f == "-0.5" -> -0.5
                    f.length > 1 && f.first() == '/' && f.last() == '/' -> "/" + f.substring(1).trimEnd('/')
                    f.length > 1 && f.first() == '#' && f.last() == '#' -> "#" + f.substring(1).trimEnd('#')
                    else -> null
                }
                if (feature != null) features[words.size] = feature
                out.append(m.groupValues[1])
                words += m.groupValues[1]
                last = m.range.last + 1
            }
            if (last < text.length) {
                out.append(text.substring(last))
                words += text.substring(last).split(WHITESPACE).filter { it.isNotEmpty() }
            }
            return Preprocessed(out.toString(), words, features)
        }

        internal fun subtokenize(word: String): List<String> = SUBTOKEN.findAll(word).map { it.value }.toList()

        private fun stressWeight(ps: String) = ps.sumOf { if (it in DIPHTHONGS) 2 else 1 as Int }

        internal fun applyStress(ps: String?, stress: Double?): String? {
            if (ps == null || stress == null) return ps
            fun restress(ps: String): String {
                val ips = ps.mapIndexed { i, c -> i.toDouble() to c }.toMutableList()
                for ((i, c) in ps.withIndex()) {
                    if (c in STRESSES) {
                        val j = (i until ps.length).first { ps[it] in VOWELS }
                        ips[i] = (j - 0.5) to c
                    }
                }
                return ips.sortedWith(compareBy({ it.first }, { it.second })).joinToString("") { it.second.toString() }
            }
            return when {
                stress < -1 -> ps.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
                stress == -1.0 || ((stress == 0.0 || stress == -0.5) && PRIMARY in ps) ->
                    ps.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
                (stress == 0.0 || stress == 0.5 || stress == 1.0) && ps.none { it in STRESSES } ->
                    if (ps.none { it in VOWELS }) ps else restress(SECONDARY + ps)
                stress >= 1 && PRIMARY !in ps && SECONDARY in ps -> ps.replace(SECONDARY, PRIMARY)
                stress > 1 && ps.none { it in STRESSES } -> if (ps.none { it in VOWELS }) ps else restress(PRIMARY + ps)
                else -> ps
            }
        }

        // misaki EspeakFallback.E2M, applied after restoring the tie bars ('^') that misaki's
        // phonemizer call asks espeak for; piper-phonemize omits them. Ties per espeak's
        // multi-character phonemes; the context rules were fitted against misaki output.
        private val E2M = listOf(
            "ʔˌn̩" to "ʔn", "ʔn̩" to "ʔn", "a^ɪ" to "I", "a^ʊ" to "W", "d^ʒ" to "ʤ", "e^ɪ" to "A",
            "t^ʃ" to "ʧ", "ɔ^ɪ" to "Y", "ə^l" to "ᵊl", "ʲo" to "jo", "ʲə" to "jə", "e" to "A", "ʲ" to "",
            "ɚ" to "əɹ", "r" to "ɹ", "x" to "k", "ç" to "k", "ɐ" to "ə", "ɬ" to "l", "̃" to "",
        ).sortedByDescending { it.first.length }
        private val TIE_ALWAYS = setOf("eɪ", "aɪ", "dʒ", "tʃ", "əʊ", "oʊ", "aʊ", "ɔɪ", "eə", "iə")
        private val TIE_VOWELS = "aeiouɑɒɔəɛɜɪʊʌæɐᵻɚ".toSet()
        private val SYLLABIC = Regex("(\\S)̩")

        private fun tied(s: String, i: Int): Boolean {
            val p = s.substring(i, i + 2)
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i + 2 < s.length) s[i + 2] else ' '
            return when (p) {
                in TIE_ALWAYS -> true
                "əl" -> prev !in TIE_VOWELS && next !in STRESSES
                "ɪə" -> prev in TIE_VOWELS && next !in TIE_VOWELS
                "ːɹ" -> next !in TIE_VOWELS && next !in STRESSES
                else -> false
            }
        }

        internal fun espeakToMisaki(raw: String, british: Boolean): String {
            val sb = StringBuilder()
            var prevTied = false
            for (i in raw.indices) {
                sb.append(raw[i])
                prevTied = if (i + 1 < raw.length && !prevTied && tied(raw, i)) { sb.append('^'); true } else false
            }
            var ps = sb.toString()
            for ((old, new) in E2M) ps = ps.replace(old, new)
            ps = SYLLABIC.replace(ps, "ᵊ$1").replace("̩", "")
            ps = if (british) {
                ps.replace("e^ə", "ɛː").replace("iə", "ɪə").replace("ə^ʊ", "Q")
            } else {
                ps.replace("o^ʊ", "O").replace("ɜːɹ", "ɜɹ").replace("ɜː", "ɜɹ").replace("ɪə", "iə").replace("ː", "")
            }
            return ps.replace('o', 'ɔ').replace('ɾ', 'T').replace('ʔ', 't').replace("^", "")
        }

        // phonemizer's Punctuation.preserve/restore (misaki asks for preserve_punctuation=True):
        // espeak sees only the text between marks, and the marks are put back verbatim.
        private const val MARKS = ";:,.!?¡¿—…\"«»“”(){}[]"
        private val MARKS_RE = Regex("(\\s*[" + MARKS.map { "\\u%04x".format(it.code) }.joinToString("") + "]+\\s*)+")

        internal fun preservePunctuation(line: String, espeak: (String) -> String?): String? {
            fun phonemize(chunk: String): String? = espeak(chunk)?.trim()?.replace("  ", " ")?.let { "$it " }
            val matches = MARKS_RE.findAll(line).toList()
            if (matches.isEmpty()) return phonemize(line)
            if (matches.size == 1 && matches[0].value == line) return line
            val marks = matches.mapIndexed { i, m ->
                val pos = when {
                    i == 0 && line.startsWith(m.value) -> 'B'
                    i == matches.lastIndex && line.endsWith(m.value) -> 'E'
                    else -> 'I'
                }
                m.value to pos
            }.toMutableList()
            val chunks = mutableListOf<String>()
            var rest = line
            for ((mark, _) in marks) {
                val parts = rest.split(mark)
                chunks += parts[0]
                rest = parts.drop(1).joinToString(mark)
            }
            chunks += rest
            val text = chunks.filter { it.isNotEmpty() }.map { phonemize(it) ?: return null }.toMutableList()
            // Punctuation.restore with sep.word=' ', strip=False; every mark has line index 0.
            val out = mutableListOf<String>()
            var pos = 0
            while (text.isNotEmpty() || marks.isNotEmpty()) {
                if (marks.isEmpty()) {
                    text.forEach { out += if (it.endsWith(" ")) it else "$it " }
                    text.clear()
                } else if (text.isEmpty()) {
                    out += marks.joinToString("") { it.first }
                    marks.clear()
                } else if (pos == 0) {
                    val (mark, position) = marks.removeAt(0)
                    if (text[0].endsWith(" ")) text[0] = text[0].dropLast(1)
                    when (position) {
                        'B' -> text[0] = mark + text[0]
                        'E' -> {
                            out += text.removeAt(0) + mark + if (mark.endsWith(" ")) "" else " "
                            pos++
                        }
                        else -> if (text.size == 1) text[0] = text[0] + mark else {
                            val first = text.removeAt(0)
                            text[0] = first + mark + text[0]
                        }
                    }
                } else {
                    out += text.removeAt(0)
                    pos++
                }
            }
            return out.firstOrNull() ?: ""
        }

        /** A packed dictionary value: "phonemes" or "TAG=ps|TAG=ps" (empty ps = none); grown at build time. */
        fun parseValue(v: String): Any =
            if ('=' in v) v.split('|').associate { kv -> kv.substringBefore('=') to kv.substringAfter('=').ifEmpty { null } } else v
    }
}

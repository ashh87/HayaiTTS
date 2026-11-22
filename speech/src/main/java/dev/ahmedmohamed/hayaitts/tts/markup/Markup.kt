package dev.ahmedmohamed.hayaitts.tts.markup

import kotlin.math.ln
import kotlin.math.min

/**
 * Speech markup -> segments. A port of the kokoro tools' `markup.py`; both run the shared
 * golden cases in test/resources/markup_cases.json, and docs/MARKUP.md is the spec.
 *
 *  - square-bracket tags anywhere: [sad], [happy:40], [pause=800ms], [rate=0.9], [pitch=+2st], [volume=-3dB]
 *  - SSML, only when the text starts with <speak>
 *
 * Emphasis (*word*, **word**, <emphasis>) and intensity changes inside a segment become
 * [MARKERS] the expressive Kokoro model reads, so the sentence stays one utterance.
 * Only recognised tags are consumed: "[1]" or "a < b" read as written, and misaki links
 * like [word](/ipa/) pass through untouched for the G2P.
 */
object Markup {

    data class Segment(
        val text: String,
        val emotion: String? = null,
        val intensity: Double = 1.0,
        val rate: Double = 1.0,
        /** Semitones. */
        val pitch: Double = 0.0,
        /** dB. */
        val volume: Double = 0.0,
        /** Silence after the text. */
        val pauseMs: Int = 0,
    )

    val EMOTIONS = listOf("happy", "sad", "angry", "calm")
    private val ALIASES = mapOf(
        "joyful" to "happy", "cheerful" to "happy", "excited" to "happy",
        "unhappy" to "sad", "melancholy" to "sad", "disappointed" to "sad",
        "annoyed" to "angry", "furious" to "angry",
        "relaxed" to "calm", "gentle" to "calm", "soothing" to "calm",
    )
    private val NEUTRAL = setOf("neutral", "default", "normal")
    private const val DEFAULT_PAUSE_MS = 500
    private const val PARAGRAPH_PAUSE_MS = 500
    private val BREAK_STRENGTH = mapOf("none" to 0, "x-weak" to 100, "weak" to 200, "medium" to 400, "strong" to 700, "x-strong" to 1200)
    private val RATE_WORDS = mapOf("x-slow" to 0.6, "slow" to 0.8, "medium" to 1.0, "default" to 1.0, "fast" to 1.2, "x-fast" to 1.4)
    private val PITCH_WORDS = mapOf("x-low" to -6.0, "low" to -3.0, "medium" to 0.0, "default" to 0.0, "high" to 3.0, "x-high" to 6.0)
    private val VOLUME_WORDS = mapOf("silent" to -60.0, "x-soft" to -12.0, "soft" to -6.0, "medium" to 0.0, "default" to 0.0, "loud" to 4.0, "x-loud" to 8.0)
    private val EMPHASIS = mapOf("strong" to 2, "moderate" to 1) // marker levels; "reduced" is a no-op
    private val AMAZON_INTENSITY = mapOf("low" to 0.4, "medium" to 0.7, "high" to 1.0)
    const val MAX_INTENSITY = 2.0

    // The expressive model's span markers (private-use characters; kokoro tools' expressive.py).
    const val EMPH_ON = '\uE000'
    const val EMPH_OFF = '\uE001'
    const val LEVEL_UP = '\uE002'
    const val LEVEL_DOWN = '\uE003'
    const val MARKERS = "$EMPH_ON$EMPH_OFF$LEVEL_UP$LEVEL_DOWN"
    const val LEVEL_STEP = 0.25

    fun stripMarkers(s: String): String = s.filterNot { it in MARKERS }

    private data class Style(
        val emotion: String? = null,
        val intensity: Double = 1.0,
        val rate: Double = 1.0,
        val pitch: Double = 0.0,
        val volume: Double = 0.0,
    )

    /** Emotion of a tag word: [Emotion.Is] (null = neutral), or [Emotion.NotOne]. */
    private sealed interface Emotion {
        data class Is(val name: String?) : Emotion
        data object NotOne : Emotion
    }

    private fun emotionOf(name: String): Emotion {
        val n = name.trim().lowercase()
        if (n in NEUTRAL) return Emotion.Is(null)
        val e = ALIASES[n] ?: n
        return if (e in EMOTIONS) Emotion.Is(e) else Emotion.NotOne
    }

    // Python float(): optional sign, digits with an optional fraction, nothing else.
    private val NUMBER = Regex("""\s*[+-]?(\d+(\.\d*)?|\.\d+)\s*""")
    private fun num(s: String): Double? = if (NUMBER.matches(s)) s.trim().toDouble() else null

    internal fun parseMs(s: String): Int? {
        val m = Regex("""\s*(\d+(?:\.\d+)?)\s*(ms|s)?\s*""", RegexOption.IGNORE_CASE).matchEntire(s) ?: return null
        val v = m.groupValues[1].toDouble()
        return Math.round(if (m.groupValues[2].equals("s", true)) v * 1000 else v).toInt()
    }

    private fun parseRate(s0: String): Double? {
        val s = s0.trim().lowercase()
        RATE_WORDS[s]?.let { return it }
        if (s.endsWith("%")) {
            val v = num(s.dropLast(1)) ?: return null
            return if (s[0] == '+' || s[0] == '-') 1 + v / 100 else v / 100
        }
        return num(s)?.takeIf { it > 0 }
    }

    private fun parsePitch(s0: String): Double? {
        val s = s0.trim().lowercase()
        PITCH_WORDS[s]?.let { return it }
        if (s.endsWith("st")) return num(s.dropLast(2))
        if (s.endsWith("%")) {
            val v = num(s.dropLast(1)) ?: return null
            return if (v <= -100) null else 12 * ln(1 + v / 100) / ln(2.0)
        }
        return if (s.endsWith("hz")) null else num(s)
    }

    private fun parseVolume(s0: String): Double? {
        val s = s0.trim().lowercase()
        VOLUME_WORDS[s]?.let { return it }
        return if (s.endsWith("db")) num(s.dropLast(2)) else num(s)
    }

    private fun parseIntensity(s0: String): Double? {
        val s = s0.trim()
        val pct = s.endsWith("%")
        val v = num(s.trimEnd('%').trim()) ?: return null
        if (v < 0) return null
        return min(if (pct || v > MAX_INTENSITY) v / 100 else v, MAX_INTENSITY)
    }

    private class Builder {
        val out = mutableListOf<Segment>()
        private val buf = StringBuilder()
        private var style = Style()
        private var level = 0 // marker level relative to the segment's starting intensity

        fun text(t: String, s: Style) {
            val cur = style
            if (s.emotion != null && cur.intensity > 0 && s.copy(intensity = cur.intensity) == cur &&
                buf.any { it !in MARKERS && it != ' ' && it != '\t' }
            ) {
                // same emotion, mid-segment: intensity changes ramp with markers inside one utterance
                val target = kotlin.math.round((s.intensity / cur.intensity - 1) / LEVEL_STEP).toInt() // half-even, as Python round()
                repeat(kotlin.math.abs(target - level)) { buf.append(if (target > level) LEVEL_UP else LEVEL_DOWN) }
                level = target
            } else if (s != cur) {
                flush()
                style = s
            }
            buf.append(t)
        }

        fun flush() {
            val t = buf.toString().replace(SPACES, " ").trim()
            buf.clear()
            level = 0
            if (t.any { it !in MARKERS }) {
                out += Segment(t, style.emotion, if (style.emotion != null) style.intensity else 1.0, style.rate, style.pitch, style.volume)
            }
        }

        fun pause(ms: Int) {
            if (ms <= 0) return
            flush()
            if (out.isEmpty()) out += Segment("")
            out[out.lastIndex] = out.last().let { it.copy(pauseMs = it.pauseMs + ms) }
        }

        fun result(): List<Segment> {
            flush()
            return out
        }
    }

    private val SPACES = Regex("[ \t]+")

    private val STARS = Regex("""(?<![\w*])(\*{1,2})(?=[^\s*])([^*\n]*?[^\s*])\1(?![\w*])""")

    /** *word* / **words** -> emphasis markers (1 or 2 levels). A lone * ("5 * 3") stays. */
    private fun stars(text: String): String = STARS.replace(text) { m ->
        val n = m.groupValues[1].length
        EMPH_ON.toString().repeat(n) + m.groupValues[2] + EMPH_OFF.toString().repeat(n)
    }

    // ---- square-bracket tags ----
    private val BRACKET = Regex("""\[([^\[\]\n]{1,40})\](?!\()""")
    private val KV = Regex("""^([a-z-]+)(?:\s*[:=]\s*|\s+)?(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** (new style, pause ms) for a recognised tag, null to read it as text. */
    private fun bracket(tag: String, style: Style): Pair<Style, Int>? {
        val m = KV.find(tag.trim()) ?: return null
        val name = m.groupValues[1].lowercase()
        val v = m.groupValues[2].trim()
        return when (name) {
            "pause", "break" -> (if (v.isEmpty()) DEFAULT_PAUSE_MS else parseMs(v))?.let { style to it }
            "rate" -> (if (v.isEmpty()) 1.0 else parseRate(v))?.let { style.copy(rate = it) to 0 }
            "pitch" -> (if (v.isEmpty()) 0.0 else parsePitch(v))?.let { style.copy(pitch = it) to 0 }
            "volume" -> (if (v.isEmpty()) 0.0 else parseVolume(v))?.let { style.copy(volume = it) to 0 }
            else -> when (val e = emotionOf(name)) {
                Emotion.NotOne -> null
                is Emotion.Is -> when {
                    e.name == null -> if (v.isEmpty()) style.copy(emotion = null, intensity = 1.0) to 0 else null
                    else -> (if (v.isEmpty()) 1.0 else parseIntensity(v))?.let { style.copy(emotion = e.name, intensity = it) to 0 }
                }
            }
        }
    }

    private fun parseBrackets(text0: String): List<Segment> {
        val text = stars(text0)
        val b = Builder()
        var style = Style()
        var at = 0
        for (m in BRACKET.findAll(text)) {
            val r = bracket(m.groupValues[1], style) ?: continue
            b.text(text.substring(at, m.range.first), style)
            style = r.first
            b.pause(r.second)
            at = m.range.last + 1
        }
        b.text(text.substring(at), style)
        return b.result()
    }

    // ---- SSML ----
    private val TAG = Regex(
        """<(/?)([A-Za-z][\w:.-]*)((?:\s+[\w:.-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(/?)>|<!--.*?-->|<\?.*?\?>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    internal fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }

    private fun attrs(s: String): Map<String, String> = ATTR.findAll(s).associate { m ->
        m.groupValues[1].lowercase().substringAfterLast(':') to unescape(if (m.groups[2] != null) m.groupValues[2] else m.groupValues[3])
    }

    private class Frame(val name: String, val style: Style, val xf: ((String) -> String)?)

    private fun parseSsml(text: String): List<Segment> {
        val b = Builder()
        val stack = mutableListOf(Frame("", Style(), null))
        var at = 0

        fun emit(raw: String) {
            if (raw.isEmpty()) return
            val top = stack.last()
            val t = unescape(raw).let { top.xf?.invoke(it) ?: it }
            b.text(t, top.style)
        }

        for (m in TAG.findAll(text)) {
            emit(text.substring(at, m.range.first))
            at = m.range.last + 1
            if (m.groups[2] == null) continue // comment / processing instruction
            val closing = m.groupValues[1].isNotEmpty()
            val name = m.groupValues[2].lowercase()
            val a = attrs(m.groupValues[3])
            val selfClose = m.groupValues[4].isNotEmpty()
            val local = name.substringAfterLast(':')
            if (closing) {
                val i = stack.indexOfLast { it.name == name }
                if (i > 0) while (stack.size > i) stack.removeAt(stack.lastIndex)
                when (local) {
                    "p" -> b.pause(PARAGRAPH_PAUSE_MS)
                    "s" -> b.flush()
                }
                continue
            }
            if (local == "break") {
                val ms = a["time"]?.let(::parseMs)
                b.pause(ms ?: BREAK_STRENGTH[(a["strength"] ?: "medium").lowercase()] ?: 400)
                continue
            }
            if (selfClose) continue
            var style = stack.last().style
            var xf = stack.last().xf
            when (local) {
                "prosody" -> {
                    val r = a["rate"]?.let(::parseRate)
                    val p = a["pitch"]?.let(::parsePitch)
                    val v = a["volume"]?.let(::parseVolume)
                    style = style.copy(rate = style.rate * (r ?: 1.0), pitch = style.pitch + (p ?: 0.0), volume = style.volume + (v ?: 0.0))
                }
                "express-as", "emotion" -> {
                    val e = emotionOf(a["style"] ?: a["name"] ?: "")
                    if (e is Emotion.Is) {
                        val i = if ("styledegree" in a) { // Azure: a 0.01..2 multiplier
                            num(a.getValue("styledegree"))?.takeIf { it > 0 }?.let { min(it, MAX_INTENSITY) } ?: 1.0
                        } else { // Amazon: low/medium/high; also accept 40% or 0.4
                            val deg = a["intensity"] ?: ""
                            AMAZON_INTENSITY[deg.lowercase()] ?: parseIntensity(deg)?.takeIf { it != 0.0 } ?: 1.0
                        }
                        style = style.copy(emotion = e.name, intensity = if (e.name != null) i else 1.0)
                    }
                }
                "emphasis" -> EMPHASIS[(a["level"] ?: "moderate").lowercase()]?.let { n ->
                    xf = { t -> if (t.isNotBlank()) EMPH_ON.toString().repeat(n) + t + EMPH_OFF.toString().repeat(n) else t }
                }
                "phoneme" -> {
                    val ph = (a["ph"] ?: "").trim().trim('/')
                    if (ph.isNotEmpty()) xf = { t -> if (t.isNotBlank()) "[${t.trim()}](/$ph/)" else t }
                }
                "sub" -> {
                    val alias = a["alias"] ?: ""
                    var done = false
                    xf = { if (done) "" else alias.also { done = true } }
                }
                "say-as" -> if ((a["interpret-as"] ?: "").lowercase() in setOf("characters", "spell-out", "letters")) {
                    xf = { t -> t.filterNot { it.isWhitespace() }.toList().joinToString(" ") }
                }
            }
            stack += Frame(name, style, xf)
        }
        emit(text.substring(at))
        return b.result()
    }

    fun parse(text: String): List<Segment> {
        if (text.trimStart().lowercase().startsWith("<speak")) {
            // Never silence: malformed SSML is read without its tags.
            return runCatching { parseSsml(text) }.getOrElse { parseBrackets(unescape(text.replace(Regex("<[^>]*>"), " "))) }
        }
        return parseBrackets(text)
    }
}

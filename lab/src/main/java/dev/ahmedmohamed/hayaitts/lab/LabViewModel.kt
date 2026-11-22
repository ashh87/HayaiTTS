package dev.ahmedmohamed.hayaitts.lab

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class LabViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lab", Context.MODE_PRIVATE)
    private val cache = File(app.filesDir, "cache")

    // One thread for the model and espeak-ng (neither is thread-safe), two for the network.
    private val synthThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val netThreads = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val player = Player()
    private var job: Job? = null

    private var server: LabServer? = null
    private var kokoro: Kokoro? = null
    private var speech: Speech? = null
    private val voices = HashMap<String, FloatArray>()

    var url by mutableStateOf(prefs.getString("url", "").orEmpty())
    var status by mutableStateOf("Not connected")
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var index by mutableStateOf<LabServer.Index?>(null)
        private set
    var updateReady by mutableStateOf(false)
        private set
    private var installedSha: String? = null

    // Mixer
    val weights = mutableStateMapOf<String, Float>()
    var emotion by mutableStateOf("neutral")
    var intensity by mutableStateOf(1f)
    var mixerText by mutableStateOf("Lovely weather for a cup of tea. Shall we take the scenic route through the park?")
    var creakResult by mutableStateOf("")
        private set

    // Phrases
    class Phrase(val tag: String, val text: String)

    var phrases by mutableStateOf<List<Phrase>>(emptyList())
        private set
    var accent by mutableStateOf<String?>(null)
    var rulesOn by mutableStateOf(true)
    val phonemes = mutableStateMapOf<Int, String>()
    val phraseCreak = mutableStateMapOf<Int, Int>()
    var playingPhrase by mutableStateOf<Int?>(null)
        private set

    // Compare
    class Item(val key: String, val label: String, val load: suspend () -> Clip)

    val recents = mutableStateListOf<Item>()
    val selected = mutableStateListOf<Item>()
    var folder by mutableStateOf<LabServer.ClipFolder?>(null)
    var blind by mutableStateOf(true)
    var note by mutableStateOf("")
    var pair by mutableStateOf<List<Item>?>(null)
        private set
    var revealed by mutableStateOf(false)
        private set

    init {
        if (url.isNotBlank()) connect()
    }

    fun cacheMb(): Long = (cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / 1_000_000

    fun clearCache() = launch("Clearing the cache") {
        withContext(synthThread) { kokoro?.close(); kokoro = null; speech = null }
        withContext(netThreads) { cache.deleteRecursively() }
        voices.clear()
        index = null
        status = "Cache cleared; connect again"
    }

    fun connect() = launch("Connecting") {
        prefs.edit().putString("url", url.trim()).apply()
        val s = LabServer(url, cache)
        val idx = withContext(netThreads) { s.index() }
        // checked first, so a broken model can't block the update that fixes it
        val mine = installedSha ?: withContext(netThreads) { Update.installedSha256(getApplication()) }.also { installedSha = it }
        updateReady = idx.app?.sha256.let { it != null && it != mine }
        if (updateReady) { server = s; index = idx }
        status = "Fetching the parts"
        val tokens: String
        val words: File
        val phraseText: String
        withContext(netThreads) {
            tokens = String(s.bytes("tokens.txt"))
            words = s.file("pronunciations.tsv", null, "pronunciations.tsv")
            phraseText = String(s.bytes("phrases.txt"))
            idx.accents.forEach { s.file("accent_$it.json", null, "accent", "$it.json") }
        }
        status = "Model (${idx.model.size / 1_000_000} MB, fetched once)"
        val model = withContext(netThreads) {
            s.file("model.onnx", "${idx.model.size}-${idx.model.mtime}", "model.onnx") { progress = it }
        }
        progress = null
        status = "Loading the model"
        withContext(synthThread) {
            kokoro?.close()
            val k = Kokoro(model, tokens)
            kokoro = k
            speech = Speech(getApplication(), k, idx.presets, idx.nControls, idx.maxIntensity, words)
        }
        server = s
        index = idx
        voices.clear()
        phrases = phraseText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val m = PHRASE.matchEntire(line)
            if (m != null) Phrase(m.groupValues[1], m.groupValues[2]) else Phrase("", line)
        }
        phonemes.clear()
        phraseCreak.clear()
        if (weights.isEmpty() || weights.keys.any { k -> idx.voices.none { it.name == k } }) {
            weights.clear()
            weights[idx.defaultVoice] = 1f
        }
        status = "Connected: ${idx.voices.size} voices, ${idx.accents.size} accents" +
            if (updateReady) ". An app update is ready (Server tab)" else ""
    }

    fun update() = launch("Downloading the update") {
        val s = server ?: error("Not connected")
        val apk = withContext(netThreads) { s.file("lab.apk", null, "lab.apk") { progress = it } }
        progress = null
        val want = index?.app?.sha256
        check(want == null || withContext(netThreads) { Update.sha256(apk) } == want) { "the download doesn't match the server's hash; try again" }
        Update.install(getApplication(), apk)
        status = "Confirm the update in Android's installer"
    }

    private suspend fun voice(name: String): FloatArray {
        voices[name]?.let { return it }
        val s = server ?: error("Not connected")
        val v = index?.voices?.firstOrNull { it.name == name } ?: error("No voice $name")
        val f = withContext(netThreads) { s.file("voice_$name.f32", v.mtime.toString(), "voice", "$name.f32") }
        val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it); voices[name] = it }
    }

    /** archetype_mixer.mix(): the weighted mean of the voices with a weight above 0. */
    private suspend fun mix(): FloatArray {
        val w = weights.filterValues { it > 0f }
        require(w.isNotEmpty()) { "Set at least one slider above 0" }
        val total = w.values.sum()
        val out = FloatArray(Kokoro.ROWS * Kokoro.STYLE)
        for ((name, x) in w) {
            val v = voice(name)
            for (i in out.indices) out[i] += v[i] * (x / total)
        }
        return out
    }

    private fun mixLabel() = weights.filterValues { it > 0f }.entries.joinToString(" ") { "${it.key} ${"%.2f".format(it.value)}" }

    private fun accentFile(): File? = accent?.takeIf { rulesOn }?.let { File(cache, "accent_$it.json") }

    private suspend fun render(text: String, accentFile: File?): Clip {
        val v = mix()
        val sp = speech ?: error("Not connected")
        return Clip(withContext(synthThread) { sp.render(text, v, accentFile) }, Kokoro.SAMPLE_RATE)
    }

    private fun remember(label: String, clip: Clip) {
        recents.add(0, Item("phone:${System.nanoTime()}", label) { clip })
        while (recents.size > 20) recents.removeAt(recents.lastIndex)
    }

    /** The mixer's emotion as a leading [tag], as the PC mixer does; tags or SSML in the text still apply. */
    private fun withEmotion(text: String): String =
        if (emotion == "neutral" || text.trimStart().startsWith("<speak", ignoreCase = true)) text
        else "[$emotion:${(intensity * 100).toInt()}%] $text"

    fun playMixer() = launch("Synthesising") {
        val clip = render(withEmotion(mixerText), null)
        player.play(clip)
        remember("Mixer · ${mixLabel()} · ${emotion}${if (emotion != "neutral") " ${(intensity * 100).toInt()}%" else ""} · ${mixerText.take(40)}", clip)
        status = "Playing ${mixLabel()}"
    }

    fun saveMix() = launch("Saving the mix on the PC") {
        val s = server ?: error("Not connected")
        val body = buildJsonObject { putJsonObject("weights") { weights.filterValues { it > 0f }.forEach { (k, v) -> put(k, v) } } }
        val reply = withContext(netThreads) { s.post("mix", body) }
        val saved = LabServer.JSON.parseToJsonElement(reply).jsonObject["saved"]?.jsonPrimitive?.content
        index = withContext(netThreads) { s.index() }
        status = "Saved $saved"
    }

    fun measureCreak() = launch("Measuring creak (${Creak.LINES.size * 2} short lines)") {
        val counts = ArrayList<Int>()
        repeat(2) { run ->
            Creak.LINES.forEachIndexed { i, line ->
                status = "Measuring creak: line ${run * Creak.LINES.size + i + 1} of ${Creak.LINES.size * 2}"
                counts += Creak.pulses(render(line, null).samples)
            }
        }
        creakResult = "Creak %.1f pulses/line (old bf_ha 3.1, clean < 3, clicky 7+)".format(counts.average())
        status = creakResult
    }

    fun playPhrase(i: Int) = launch("Synthesising") { phrase(i) }

    private suspend fun phrase(i: Int): Clip {
        val p = phrases[i]
        val clip = render(p.text, accentFile())
        phraseCreak[i] = Creak.pulses(clip.samples)
        playingPhrase = i
        player.play(clip)
        val a = accent?.takeIf { rulesOn } ?: "no accent"
        remember("Phrase · ${mixLabel()} · $a · ${p.text.take(40)}", clip)
        status = "Playing: ${p.text}"
        return clip
    }

    fun playAll() = launch("Playing all phrases") {
        try {
            for (i in phrases.indices) {
                delay(phrase(i).millis + 400)
            }
        } finally {
            playingPhrase = null
        }
        status = "Played ${phrases.size} phrases"
    }

    fun showPhonemes(i: Int) = launch("Phonemising") {
        val sp = speech ?: error("Not connected")
        val text = phrases[i].text
        val a = accent?.let { File(cache, "accent_$it.json") }
        phonemes[i] = withContext(synthThread) {
            "misaki: " + sp.phonemes(text, null) + (a?.let { "\n$accent: " + sp.phonemes(text, it) } ?: "")
        }
        status = "Phonemes for phrase ${i + 1}"
    }

    fun serverItems(f: LabServer.ClipFolder): List<Item> = f.files.map { file ->
        Item("server:${f.root}/$file", "${f.name}/${file.substringAfterLast('/')}") {
            val s = server ?: error("Not connected")
            readWav(withContext(netThreads) { s.bytes("clips", f.root.toString(), *file.split('/').toTypedArray()) })
        }
    }

    fun toggle(item: Item) {
        val at = selected.indexOfFirst { it.key == item.key }
        if (at >= 0) selected.removeAt(at) else {
            if (selected.size == 2) selected.removeAt(0)
            selected += item
        }
        pair = null
    }

    fun startPair() {
        if (selected.size != 2) return
        pair = if (blind) selected.shuffled() else selected.toList()
        revealed = !blind
        note = ""
        status = if (blind) "Blind: listen to A and B, then vote" else "Listen to A and B, then vote"
    }

    fun playSide(side: Int) = launch("Loading ${"AB"[side]}") {
        val item = pair?.getOrNull(side) ?: return@launch
        player.play(item.load())
        status = "Playing ${"AB"[side]}" + if (revealed) ": ${item.label}" else ""
    }

    fun vote(choice: String) = launch("Sending the verdict") {
        val (a, b) = pair ?: return@launch
        val s = server ?: error("Not connected")
        val winner = when (choice) { "A" -> a.label; "B" -> b.label; else -> "same" }
        val body = buildJsonObject {
            put("a", a.label); put("b", b.label); put("blind", blind); put("choice", choice)
            put("winner", winner); put("note", JsonPrimitive(note.trim()))
        }
        withContext(netThreads) { s.post("verdicts", body) }
        revealed = true
        status = "Sent. A was ${a.label}; B was ${b.label}"
    }

    fun stop() {
        job?.cancel()
        player.stop()
        playingPhrase = null
        busy = false
        status = "Stopped"
    }

    /** Runs [work] as the one current task (a new one replaces it), with errors shown in [status]. */
    private fun launch(what: String, work: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            status = "$what…"
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "$what failed: ${e.message ?: e.javaClass.simpleName}"
                progress = null
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        player.stop()
        kokoro?.close()
        synthThread.close()
        netThreads.close()
    }

    companion object {
        private val PHRASE = Regex("""\[([^\]]*)]\s*(.*)""")
    }
}

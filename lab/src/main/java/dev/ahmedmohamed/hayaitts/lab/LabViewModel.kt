package dev.ahmedmohamed.hayaitts.lab

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
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
    /** Downloads in progress: file name -> fraction done, one bar each. */
    val downloads = mutableStateMapOf<String, Float>()
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
    /** The emotion presets every render uses: [presetVoice]'s from the server, as edited by the sliders. */
    val presets = mutableStateMapOf<String, List<Double>>()
    var presetVoice by mutableStateOf<String?>(null)
        private set

    // Phrases
    class Phrase(val tag: String, val text: String)

    var phrases by mutableStateOf<List<Phrase>>(emptyList())
        private set
    var accent by mutableStateOf<String?>(null)
    /** A pack voice for the Phrases screen (its accent comes with it); null plays the Mixer's sliders. */
    var phraseVoice by mutableStateOf<String?>(null)
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

    // Questions from Claude
    var questions by mutableStateOf<List<LabServer.Question>>(emptyList())
        private set
    /** Per question: the clip indices in the order shown (shuffled when blind). */
    val order = mutableStateMapOf<String, List<Int>>()
    val choice = mutableStateMapOf<String, String>()
    val reply = mutableStateMapOf<String, String>()
    val answeredNow = mutableStateMapOf<String, String>()

    /** Questions skipped for now: shown after the others. */
    val skipped = mutableStateListOf<String>()

    /** Open questions in the order to answer them: by set, skipped ones last. */
    fun openQuestions(): List<LabServer.Question> =
        questions.filter { !it.answered && it.id !in answeredNow }.sortedBy { it.id in skipped }

    fun skip(q: LabServer.Question) {
        skipped.remove(q.id)
        skipped += q.id
    }

    init {
        if (url.isNotBlank()) connect(auto = true)
        Questions.schedule(app)
        viewModelScope.launch { snapshotFlow { Update.result }.filterNotNull().collect { status = it; Update.result = null } }
        viewModelScope.launch {
            while (true) { // while the app is open, look for new questions every minute
                delay(60_000)
                if (server != null) runCatching { loadQuestions() }
            }
        }
    }

    fun refreshQuestions() = launch("Fetching questions") { status = "${loadQuestions()} open question(s) from Claude" }

    /** Fetches the questions; returns how many are still open. */
    private suspend fun loadQuestions(): Int {
        val s = server ?: error("Not connected")
        questions = withContext(netThreads) { s.questions() }
        Questions.markSeen(getApplication(), questions.map { it.id }) // seen in the app: no notification for them
        for (q in questions) order.getOrPut(q.id) { q.clips.indices.toList().let { if (q.blind) it.shuffled() else it } }
        return questions.count { !it.answered && it.id !in answeredNow }
    }

    /** What the n-th clip shown for [q] is called right now: just a letter while a blind question is open. */
    fun clipName(q: LabServer.Question, shown: Int): String {
        val letter = ('A' + shown).toString()
        val hidden = q.blind && !q.answered && q.id !in answeredNow
        return if (hidden) letter else "$letter: ${q.clips[order[q.id]!![shown]]}"
    }

    fun playQuestionClip(q: LabServer.Question, shown: Int) = launch("Loading ${'A' + shown}") {
        val s = server ?: error("Not connected")
        val n = order[q.id]!![shown]
        player.play(readWav(withContext(netThreads) { s.bytes("qclip", q.id, "$n.wav") }))
        status = "Playing ${clipName(q, shown)}"
    }

    fun answer(q: LabServer.Question) = launch("Sending your answer") {
        val s = server ?: error("Not connected")
        val c = choice[q.id]
        val t = reply[q.id]?.trim().orEmpty()
        require(c != null || t.isNotEmpty()) { "Pick an option or type a reply" }
        val shown = order[q.id]!!.map { q.clips[it] }
        val body = buildJsonObject {
            put("id", q.id); put("question", q.question)
            c?.let { put("choice", it) }; put("text", t)
            putJsonObject("order") { shown.forEachIndexed { i, label -> put(('A' + i).toString(), label) } }
        }
        withContext(netThreads) { s.post("answers", body) }
        answeredNow[q.id] = listOfNotNull(c, t.ifEmpty { null }).joinToString(" · ")
        status = "Answer sent" + if (q.blind) ": " + shown.mapIndexed { i, l -> "${'A' + i} was $l" }.joinToString(", ") else ""
    }

    fun cacheMb(): Long = (cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / 1_000_000

    fun clearCache() = launch("Clearing the cache") {
        withContext(synthThread) { kokoro?.close(); kokoro = null; speech = null }
        withContext(netThreads) { cache.deleteRecursively() }
        voices.clear()
        index = null
        status = "Cache cleared; connect again"
    }

    /** [auto]: on opening the app, which only uses a model already on the phone (a new one waits for Connect). */
    fun connect(auto: Boolean = false) = launch("Connecting") {
        prefs.edit().putString("url", url.trim()).apply()
        val s = LabServer(url, cache)
        val idx = withContext(netThreads) { s.index() }
        // checked first, so a broken model can't block the update that fixes it
        val mine = installedSha ?: withContext(netThreads) { Update.installedSha256(getApplication()) }.also { installedSha = it }
        updateReady = idx.app?.sha256.let { it != null && it != mine }
        if (updateReady) { // update first: the new build may want a different model anyway
            server = s; index = idx
            status = "App update ready: tap Update below, then Connect"
            return@launch
        }
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
        val stamp = "${idx.model.size}-${idx.model.mtime}"
        // a worker with a foreground notification, so the download carries on when you switch apps
        val cached = withContext(netThreads) { s.cached("model.onnx", stamp) }
        if (cached == null && auto) {
            server = s
            val nOpen = runCatching { loadQuestions() }.getOrDefault(0)
            status = "A new model is on the server (${idx.model.size / 1_000_000} MB): tap Connect to download it" +
                if (nOpen > 0) ". $nOpen question(s) from Claude (Compare tab)" else ""
            return@launch
        }
        val model = cached
            ?: download("model.onnx") { ModelDownload.fetch(getApplication(), url, "model.onnx", stamp, "model.onnx", progress = it) }
        status = "Loading the model"
        withContext(synthThread) {
            kokoro?.close()
            val k = Kokoro(model, tokens)
            kokoro = k
            speech = Speech(getApplication(), k, presets, idx.nControls, idx.maxIntensity, words)
        }
        server = s
        index = idx
        pickPresets(presetVoice?.takeIf { it in idx.voicePresets } ?: idx.voicePresets.keys.firstOrNull())
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
        val nOpen = runCatching { loadQuestions() }.getOrDefault(0)
        status = "Connected: ${idx.voices.size} voices, ${idx.accents.size} accents" +
            (if (nOpen > 0) ". $nOpen question(s) from Claude (Compare tab)" else "") +
            if (updateReady) ". An app update is ready (Server tab)" else ""
    }

    fun update() = install("lab.apk", index?.app)

    /** HayaiTTS is older than the server's build (or not installed). */
    fun mainUpdateReady(): Boolean =
        index?.mainApp?.let { m -> Update.mainInstalledAt(getApplication()).let { it == null || it < m.mtime } } ?: false

    fun updateMain() = install("main.apk", index?.mainApp)

    /** Downloads [b] (in the background, like the model) and opens it in HayaiTTS's import screen. */
    fun sendBundle(b: LabServer.Bundle) = launch("Downloading ${b.name}", alone = false) { // alongside other tasks, not replacing them
        val f = download("${b.name}.tar") { ModelDownload.fetch(getApplication(), url, "${b.name}.tar", "${b.size}-${b.mtime}", "bundle", "${b.name}.tar", progress = it) }
        // shortcut: if the download finished while the lab was in the background, Android may block this; tap Send again (it's cached)
        Update.sendToMain(getApplication(), f)
        status = "Opened ${b.name} in HayaiTTS: finish the import there"
    }

    private fun install(name: String, info: LabServer.Stamp?) = launch("Downloading $name") {
        val s = server ?: error("Not connected")
        val stamp = info?.let { "${it.size}-${it.mtime}" } ?: error("The server has no $name")
        // a background worker, like the model; a finished download is reused
        val apk = withContext(netThreads) { s.cached(name, stamp) }
            ?: download(name) { ModelDownload.fetch(getApplication(), url, name, stamp, name, progress = it) }
        val want = info.sha256
        if (want != null && withContext(netThreads) { Update.sha256(apk) } != want) {
            apk.delete()
            error("the download doesn't match the server's hash; try again")
        }
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

    private suspend fun render(text: String, accentFile: File?, voiceName: String? = null): Clip {
        val v = voiceName?.let { voice(it) } ?: mix()
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

    fun saveMix() = launch("Saving the mix on the PC") { status = "Saved ${postMix()}" }

    /** Saves the mixer's sliders on the PC; returns the saved voice's name. */
    private suspend fun postMix(): String {
        val s = server ?: error("Not connected")
        val body = buildJsonObject { putJsonObject("weights") { weights.filterValues { it > 0f }.forEach { (k, v) -> put(k, v) } } }
        val reply = withContext(netThreads) { s.post("mix", body) }
        index = withContext(netThreads) { s.index() }
        return LabServer.JSON.parseToJsonElement(reply).jsonObject["name"]?.jsonPrimitive?.content ?: error("no name in $reply")
    }

    /** The mixer's sliders become bundle voice [voice] (pack.json on the PC); Build pack makes the bundle. */
    fun useInPack(voice: String) = launch("Saving the mix as $voice") {
        val s = server ?: error("Not connected")
        val name = postMix()
        withContext(netThreads) { s.post("pack", buildJsonObject { put("voice", voice); put("source", name) }) }
        index = withContext(netThreads) { s.index() }
        status = "$voice is now $name. Build pack (Server tab) to make the bundle"
    }

    var buildLog by mutableStateOf<List<String>>(emptyList())
        private set

    /** Builds the bundle on the PC (pack.json + prosody/), following its progress until it's done. */
    fun buildPack() = launch("Starting the build on the PC", alone = false) {
        val s = server ?: error("Not connected")
        withContext(netThreads) { s.post("build", buildJsonObject {}) }
        while (true) {
            val b = withContext(netThreads) { s.build() }
            buildLog = b.log
            status = b.log.lastOrNull() ?: "Building…"
            if (!b.running) {
                index = withContext(netThreads) { s.index() }
                if (b.ok == true) status = "Pack built: Send it to HayaiTTS below"
                return@launch
            }
            delay(3_000)
        }
    }

    /** Load [voice]'s presets from the server's copy (null: the shared defaults). */
    fun pickPresets(voice: String?) {
        val idx = index ?: return
        presetVoice = voice
        presets.clear()
        presets.putAll(idx.voicePresets[voice] ?: idx.presets)
    }

    fun setPreset(i: Int, v: Double) {
        presets[emotion] = presets[emotion]!!.toMutableList().also { it[i] = v }
    }

    fun savePreset() = launch("Saving $emotion for $presetVoice on the PC") {
        val s = server ?: error("Not connected")
        val voice = presetVoice ?: error("The server has no bundle voices")
        val values = presets[emotion] ?: error("No $emotion preset")
        val body = buildJsonObject {
            put("voice", voice); put("emotion", emotion)
            put("values", kotlinx.serialization.json.JsonArray(values.map { JsonPrimitive(it) }))
        }
        withContext(netThreads) { s.post("prosody", body) }
        index = withContext(netThreads) { s.index() } // so Reset goes back to what was just saved
        status = "Saved $emotion for $voice in prosody/ (rebuild the bundle to hear it in HayaiTTS)"
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
        val clip = render(p.text, accentFile(), phraseVoice)
        phraseCreak[i] = Creak.pulses(clip.samples)
        playingPhrase = i
        player.play(clip)
        val a = accent?.takeIf { rulesOn } ?: "no accent"
        remember("Phrase · ${phraseVoice ?: mixLabel()} · $a · ${p.text.take(40)}", clip)
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

    /** Runs [get] with its own progress bar under [name]. */
    private suspend fun <T> download(name: String, get: suspend (progress: (Float) -> Unit) -> T): T {
        downloads[name] = 0f
        try {
            return get { downloads[name] = it }
        } finally {
            downloads.remove(name)
        }
    }

    /** Runs [work] as the one current task (a new one replaces it; with [alone] false, alongside it), with errors shown in [status]. */
    private fun launch(what: String, alone: Boolean = true, work: suspend () -> Unit) {
        if (alone) job?.cancel()
        val j = viewModelScope.launch {
            busy = true
            status = "$what…"
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "$what failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
        if (alone) job = j
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

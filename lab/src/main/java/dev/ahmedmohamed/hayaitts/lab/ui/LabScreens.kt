package dev.ahmedmohamed.hayaitts.lab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ahmedmohamed.hayaitts.lab.LabViewModel
import kotlin.math.roundToInt

private val TABS = listOf("Server", "Mixer", "Phrases", "Compare")
private val GROUPS = listOf("mix" to "Mixes (bf_harriet/)", "walk" to "Walks", "start" to "Start", "saved" to "Saved mixes", "stock" to "Stock voices")

@Composable
fun LabApp(vm: LabViewModel, openTab: MutableIntState) {
    var tab by rememberSaveable { mutableIntStateOf(if (vm.url.isBlank()) 0 else 1) }
    LaunchedEffect(openTab.intValue) {
        if (openTab.intValue >= 0) { tab = openTab.intValue; openTab.intValue = -1 }
    }
    val waiting = vm.openQuestions().size
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, name ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, label = { Text(name) },
                        icon = {
                            BadgedBox(badge = { if (i == 3 && waiting > 0) Badge { Text("$waiting") } }) {
                                Text(name.take(1), fontWeight = FontWeight.Bold)
                            }
                        },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(vm.status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                vm.downloads.forEach { (name, done) ->
                    Text("$name ${(done * 100).toInt()} %", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { done }, modifier = Modifier.fillMaxWidth())
                }
                if (vm.downloads.isEmpty() && vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            HorizontalDivider()
            when (tab) {
                0 -> ServerScreen(vm)
                1 -> MixerScreen(vm)
                2 -> PhrasesScreen(vm)
                else -> CompareScreen(vm)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun ServerScreen(vm: LabViewModel) = Column(Modifier.verticalScroll(rememberScrollState())) {
    Section("Lab server (kokoro tools lab_server.py)") {
        OutlinedTextField(vm.url, { vm.url = it }, Modifier.fillMaxWidth(), label = { Text("URL, e.g. 100.76.99.5:8770") }, singleLine = true)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connect() }, enabled = vm.url.isNotBlank()) { Text("Connect") }
            OutlinedButton(onClick = vm::clearCache) { Text("Clear cache") }
        }
        val idx = vm.index
        Text(
            if (idx == null) "Not connected." else
                "Model ${idx.model.size / 1_000_000} MB · ${idx.voices.size} voices · accents: ${idx.accents.joinToString().ifEmpty { "none" }} · ${idx.clips.size} clip folders",
            Modifier.padding(top = 12.dp),
        )
        Text("Cache: ${remember(vm.index, vm.status) { vm.cacheMb() }} MB on the phone", style = MaterialTheme.typography.bodySmall)
        idx?.app?.let { app ->
            Text(
                if (vm.updateReady) "App update ready (${app.size / 1_000_000} MB, built ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(app.mtime * 1000))})"
                else "App is up to date with the server's build.",
                Modifier.padding(top = 12.dp),
            )
            if (vm.updateReady) Button(onClick = vm::update) { Text("Update") }
        }
        idx?.mainApp?.let { app ->
            val ready = remember(vm.index, vm.status) { vm.mainUpdateReady() }
            Text(
                if (ready) "HayaiTTS: a newer build is on the server (${app.size / 1_000_000} MB)"
                else "HayaiTTS is up to date with the server's build.",
                Modifier.padding(top = 12.dp),
            )
            if (ready) Button(onClick = vm::updateMain) { Text("Install HayaiTTS") }
        }
        if (!idx?.bundles.isNullOrEmpty()) Text("Voice bundles for HayaiTTS", Modifier.padding(top = 12.dp))
        idx?.bundles?.forEach { b ->
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.sendBundle(b) }) { Text("Send") }
                Text("${b.name} (${b.size / 1_000_000} MB)", Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun PlayStop(vm: LabViewModel, play: () -> Unit, label: String = "Play") {
    Button(onClick = play) { Text(label) }
    OutlinedButton(onClick = vm::stop) { Text("Stop") }
}

@Composable
private fun MixerScreen(vm: LabViewModel) {
    val idx = vm.index ?: return Section("Mixer") { Text("Connect to the lab server first.") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Text") {
                OutlinedTextField(vm.mixerText, { vm.mixerText = it }, Modifier.fillMaxWidth(), minLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("neutral") + idx.presets.keys).forEach { e ->
                        FilterChip(vm.emotion == e, { vm.emotion = e }, label = { Text(e) })
                    }
                }
                if (vm.emotion != "neutral") {
                    Text("Intensity ${(vm.intensity * 100).roundToInt()}%")
                    Slider(vm.intensity, { vm.intensity = (it * 20).roundToInt() / 20f }, valueRange = 0f..idx.maxIntensity.toFloat())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayStop(vm, vm::playMixer)
                    OutlinedButton(onClick = vm::saveMix) { Text("Save on PC") }
                    OutlinedButton(onClick = vm::measureCreak) { Text("Creak") }
                }
                if (vm.creakResult.isNotEmpty()) Text(vm.creakResult, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.weights.keys.toList().forEach { vm.weights[it] = 0f } }) { Text("All sliders to 0") }
            }
        }
        for ((group, title) in GROUPS) {
            val vs = idx.voices.filter { it.group == group }
            if (vs.isEmpty()) continue
            item(key = "h_$group") { Text(title, Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall) }
            items(vs, key = { it.name }) { v ->
                val w = vm.weights[v.name] ?: 0f
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val mark = if (v.name == idx.installed) "  ★ installed" else ""
                        Text("${v.name}  ${"%.2f".format(w)}$mark", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            fontWeight = if (mark.isNotEmpty()) FontWeight.Bold else null)
                        Slider(w, { vm.weights[v.name] = (it * 20).roundToInt() / 20f })
                    }
                }
            }
        }
    }
}

@Composable
private fun PhrasesScreen(vm: LabViewModel) {
    val idx = vm.index ?: return Section("Phrases") { Text("Connect to the lab server first.") }
    Column(Modifier.fillMaxSize()) {
        Section("Voice: the mixer's sliders. Accent:") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                FilterChip(vm.accent == null, { vm.accent = null }, label = { Text("none") })
                idx.accents.forEach { a -> FilterChip(vm.accent == a, { vm.accent = a }, label = { Text(a) }) }
                if (vm.accent != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rules")
                        Spacer(Modifier.width(6.dp))
                        Switch(vm.rulesOn, { vm.rulesOn = it })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlayStop(vm, vm::playAll, "Play all") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(vm.phrases) { i, p ->
                Column(
                    Modifier.fillMaxWidth().clickable { vm.playPhrase(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (p.tag.isNotEmpty()) Text(p.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(p.text, fontWeight = if (vm.playingPhrase == i) FontWeight.Bold else null)
                        }
                        vm.phraseCreak[i]?.let { Text("creak $it", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { if (vm.phonemes.containsKey(i)) vm.phonemes.remove(i) else vm.showPhonemes(i) }) { Text("/ə/") }
                    }
                    vm.phonemes[i]?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CompareScreen(vm: LabViewModel) {
    val idx = vm.index
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Questions from Claude", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = vm::refreshQuestions, enabled = idx != null) { Text("Refresh") }
            }
        }
        val open = vm.openQuestions()
        val done = vm.questions.filter { it.answered || it.id in vm.answeredNow }
        val current = open.firstOrNull()
        if (current == null) item { Text("No open questions.", Modifier.padding(horizontal = 16.dp)) }
        else item(key = "q_" + current.id) {
            current.set?.let { set ->
                val inSet = vm.questions.filter { it.set == set }
                Text(
                    "$set: ${inSet.count { it.answered || it.id in vm.answeredNow } + 1} of ${inSet.size}" +
                        if (open.size > 1) " · ${open.size - 1} more waiting" else "",
                    Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelMedium,
                )
            } ?: if (open.size > 1) Text("${open.size - 1} more waiting", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelMedium) else Unit
            QuestionCard(vm, current)
        }
        items(done, key = { "q_" + it.id }) { q ->
            Text(
                "✓ ${q.question}" + (vm.answeredNow[q.id]?.let { " — $it" } ?: ""),
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
        item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        item {
            Section("Pick two clips") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Blind (hide which is which until you vote)", Modifier.weight(1f))
                    Switch(vm.blind, { vm.blind = it })
                }
                Text("Selected: " + vm.selected.joinToString("  ·  ") { it.label }.ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::startPair, enabled = vm.selected.size == 2) { Text("Compare these two") }
                vm.pair?.let { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.playSide(0) }) { Text("Play A") }
                        Button(onClick = { vm.playSide(1) }) { Text("Play B") }
                        OutlinedButton(onClick = vm::stop) { Text("Stop") }
                    }
                    if (vm.revealed) Text("A: ${pair[0].label}\nB: ${pair[1].label}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.vote("A") }) { Text("A better") }
                        OutlinedButton(onClick = { vm.vote("B") }) { Text("B better") }
                        OutlinedButton(onClick = { vm.vote("same") }) { Text("Same") }
                    }
                }
            }
        }
        item { Text("Rendered on the phone (last 20)", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall) }
        if (vm.recents.isEmpty()) item { Text("Nothing yet: play something in Mixer or Phrases.", Modifier.padding(horizontal = 16.dp)) }
        items(vm.recents, key = { it.key }) { ClipRow(vm, it) }
        item { Text("Clips on the PC", Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall) }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                idx?.clips?.forEach { f -> FilterChip(vm.folder == f, { vm.folder = if (vm.folder == f) null else f }, label = { Text("${f.name} (${f.files.size})") }) }
                    ?: Text("Connect to the lab server to see them.")
            }
        }
        vm.folder?.let { f -> items(vm.serverItems(f), key = { it.key }) { ClipRow(vm, it) } }
    }
}

@Composable
private fun QuestionCard(vm: LabViewModel, q: dev.ahmedmohamed.hayaitts.lab.LabServer.Question) {
    Section(q.question) {
        if (q.blind) Text("Blind: the clips are shuffled and named after you answer.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            q.clips.indices.forEach { i -> OutlinedButton(onClick = { vm.playQuestionClip(q, i) }) { Text("▶ " + vm.clipName(q, i)) } }
            TextButton(onClick = vm::stop) { Text("Stop") }
        }
        if (q.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                q.options.forEach { o ->
                    FilterChip(vm.choice[q.id] == o, { if (vm.choice[q.id] == o) vm.choice.remove(q.id) else vm.choice[q.id] = o }, label = { Text(o) })
                }
            }
        }
        if (q.text) {
            OutlinedTextField(vm.reply[q.id].orEmpty(), { vm.reply[q.id] = it }, Modifier.fillMaxWidth(), label = { Text("Your reply (optional)") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.answer(q) }, enabled = vm.choice[q.id] != null || !vm.reply[q.id].isNullOrBlank()) { Text("Send") }
            if (vm.openQuestions().size > 1) OutlinedButton(onClick = { vm.skip(q) }) { Text("Skip for now") }
        }
    }
}

@Composable
private fun ClipRow(vm: LabViewModel, item: LabViewModel.Item) {
    Row(Modifier.fillMaxWidth().clickable { vm.toggle(item) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(vm.selected.any { it.key == item.key }, { vm.toggle(item) })
        Text(item.label, style = MaterialTheme.typography.bodySmall)
    }
}

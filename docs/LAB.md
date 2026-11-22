# Kokoro Lab

A separate app (`:lab`, "Kokoro Lab") for listening checks on the accent voice pack: the kokoro tools'
archetype mixer, problem phrases with accent rules, blind A/B comparisons and the creak meter, on the phone.

It runs the shipping expressive `model.onnx` with ONNX Runtime, not sherpa, so a voice mixed on the phone
can drive it directly. The text front end is the same code HayaiTTS uses: the `:speech` module, which holds
misaki, markup, accent rules and the espeak fallback.

## Setup
1. On the PC, in the kokoro tools folder:
   `PYTHONPATH=. kvoicewalk/.venv/bin/python lab_server.py --host <LAN or Tailscale IP> [--port 8770] [--clips DIR ...]`
2. Build and install: `./gradlew :lab:assembleDebug`, then install `lab/build/outputs/apk/debug/lab-debug.apk`.
3. In the app, go to Server, enter the server's IP and port, and tap Connect. The 325 MB model is downloaded once.
   Voices are fetched as each slider is first used.

## Updates
The server offers the lab APK as last built (`lab/build/outputs/apk/debug/lab-debug.apk`, or `--apk`), with
its SHA-256 in `index.json`. On Connect, the app compares that hash with its own APK. If they differ, the Server
tab shows **Update**. That downloads the new APK, checks its hash, and hands it to Android's installer, which asks
you to confirm. The first time, Android also asks you to allow installs from Kokoro Lab. Builds from the same PC
share its debug signing key, so they install over each other.

## Screens
- **Mixer:** one slider per voice: walks, `bf_ha/` mixes, saved mixes and a few stock voices. Weights are
  normalised. You also get an emotion, the text, Play, **Save on PC** (written to `out/gmix_….pt`) and **Creak**
  (fry pulses per line: under ~3 is clean, 7+ is clicky).
- **Phrases:** `problem_phrases.txt`, played with the mixer's voice and an accent, with the rules switched on or
  off. **/ə/** shows misaki's phonemes beside the phonemes after the accent rules. Each phrase shows its creak
  count once played.
- **Compare:** pick two clips, from the phone's last 20 renders or the server's clip folders. Choose blind if
  you like, play A and B, and vote. Votes are written to `out/lab_verdicts.jsonl` on the PC.
  At the top are **questions from Claude**: listening checks written on the PC into `out/lab_questions.json`,
  each with clips, answer options and an optional typed reply. Blind questions shuffle the clips and name them
  after you answer. Answers are written to `out/lab_answers.jsonl`; answered questions stay ticked. The format is
  in `lab_server.py`'s docstring. Questions come one at a time, in sets ("Glottal t grid: 2 of 6"), and you can
  skip one for now. The Compare tab shows how many are waiting. The app checks every minute while it's open. In
  the background it checks every 15 minutes (Android may wait longer) and posts a notification for new ones;
  tapping it opens Compare.

## Checks
`./gradlew :lab:testDebugUnitTest` runs these checks against `lab/src/test/resources/lab_fixture.json`, written by the
kokoro tools' `lab_fixture.py`:
- ONNX Runtime durations must equal torch's. This needs `../kokoro/build/bf_ha-expressive`, or `KOKORO_BUNDLE`.
- The creak counts must equal `creak.py`'s.

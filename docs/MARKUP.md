# Speech markup

Used by both stacks: the Linux tools (`markup.py`, `tts.py` and the mixers) and HayaiTTS (`tts/markup/Markup.kt`). Both parse the shared golden cases in `markup_cases.json`.

Only recognised tags are acted on. Anything else, such as `[1]`, `[citation needed]` or `a < b`, is read exactly as written.

## Square-bracket tags (always on)

| Tag | Effect |
|---|---|
| `[happy]` `[sad]` `[angry]` `[calm]` | The emotion applies until the next emotion tag. |
| `[sad:40]`, `[sad 40%]`, `[sad:0.4]` | The same emotion at 40 % intensity. Intensity runs from 0 to 125 %. |
| `[neutral]` / `[default]` | Ends the emotion. |
| `[pause]`, `[pause=800ms]`, `[break 1.5s]` | Silence. The default is 500 ms, and a number with no unit means milliseconds. |
| `[rate=0.8]`, `[rate=+20%]`, `[rate]` | A speed multiplier. A bare `[rate]` resets it. |
| `[pitch=+2st]`, `[pitch=-10%]`, `[pitch]` | Pitch in semitones, or as a percentage. A bare tag resets it. |
| `[volume=-6dB]`, `[volume]` | Loudness in dB. A bare tag resets it. |

Emotion aliases:

| Alias | Emotion |
|---|---|
| joyful, cheerful, excited | happy |
| unhappy, melancholy, disappointed | sad |
| annoyed, furious | angry |
| relaxed, gentle, soothing | calm |

misaki's own links pass straight through to the G2P:
- `[Niamh](/nˈiːv/)` sets a pronunciation.
- `[word](+1)` changes stress.

## SSML (only when the text starts with `<speak>`)

Bracket tags are read as text inside SSML.

| Element | Effect |
|---|---|
| `<p>` | A 500 ms pause after it. |
| `<s>` | A sentence boundary. |
| `<break time="300ms"/>`, `<break strength="strong"/>` | Silence. The strengths are none 0, x-weak 100, weak 200, medium 400 (the default), strong 700 and x-strong 1200 ms. |
| `<prosody rate pitch volume>` | Values nest: rate multiplies, while pitch and volume add. See the values listed below this table. |
| `<emphasis level="strong\|moderate\|reduced">` | Stress +2 / +1 / -1 on each word, through misaki links. |
| `<phoneme alphabet="ipa" ph="nˈiːv">Niamh</phoneme>` | A pronunciation, in Kokoro's phoneme set. |
| `<sub alias="…">` | Reads the alias instead of the text. |
| `<say-as interpret-as="characters">` | Spells the text out. |
| `<mstts:express-as style="sad" styledegree="0.5">` (Azure) | An emotion. `styledegree` is the intensity, from 0.01 to 2, capped at 1.25. |
| `<amazon:emotion name="excited" intensity="medium">` | An emotion. The intensities are low 0.4, medium 0.7 and high 1.0. |

`<prosody>` values:
- **rate:** x-slow 0.6, slow 0.8, medium 1, fast 1.2 and x-fast 1.4. Also `80%` (absolute) or `+10%` (relative), or a plain multiplier.
- **pitch:** x-low −6, low −3, medium 0, high +3 and x-high +6 st. Also `+2st` or `+10%`. Hz values are ignored.
- **volume:** silent, x-soft −12, soft −6, medium 0, loud +4 and x-loud +8 dB. Also `-3dB`.

Other behaviour:
- Unknown elements are dropped, but their text is kept.
- Entities such as `&amp;` are decoded.
- Malformed SSML is read as plain text, never as silence.

## How it sounds

Emotions come from the expressive Kokoro model (`expressive.py`). It adds 8 controls after the 256-dim voice vector:
- pitch level, range and tilt
- energy
- pause length at punctuation
- pace
- gain

These are set from presets based on Kokoro Studio's joyful, sad, angry and calm.

**Linux:** intensity and pitch can take any value.

**Phone:** the expressive bundle holds 45 fixed rows, listed in `expressive.txt`:
- emotions at 50 % and 100 %
- pitch at −4, −2, 0, +2 and +4 st

The phone uses the nearest row. Rate and volume are applied per segment, and work on every voice.

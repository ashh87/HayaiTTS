Parity fixtures for `MisakiEnglishTest`.

- `golden.tsv`: accent, sentence, misaki 0.9 phonemes (Kokoro KPipeline settings), spaCy en_core_web_sm tokens/tags.
  Sentences are from UD_English-EWT (CC BY-SA 4.0) test split plus a few hand-written ones.
- `espeak.tsv`: piper-phonemize 1.5.9 output for every span the fallback might be asked for.

Regenerate with the scripts in tools/g2p/.

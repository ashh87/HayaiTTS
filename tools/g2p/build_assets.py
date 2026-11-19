"""Regenerate app/src/main/assets/g2p/ (the on-device port of misaki's English G2P).

Needs a Python env with misaki and spaCy en_core_web_sm. Steps:
  1. Download UD_English-EWT and UD_English-GUM .conllu files here, run spacy_tag.py (spaCy tags = training target).
  2. python train_tagger.py   -> pos_tagger.json (averaged perceptron imitating spaCy's tagger)
  3. python build_assets.py   -> converts misaki's dictionaries, spaCy's tokenizer rules and the tagger
"""
import json, re
from pathlib import Path
import misaki.data, spacy

OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/g2p"
OUT.mkdir(parents=True, exist_ok=True)

# misaki dictionaries -> "word<TAB>phonemes" or "word<TAB>TAG=ps|TAG=ps" (empty ps = no pronunciation)
for name in ("gb_gold", "gb_silver", "us_gold", "us_silver"):
    d = json.loads((Path(misaki.data.__file__).parent / f"{name}.json").read_text(encoding="utf-8"))
    with open(OUT / f"{name}.tsv", "w", encoding="utf-8") as f:
        for k, v in d.items():
            assert "\t" not in k and "\n" not in k
            f.write(f"{k}\t{v if isinstance(v, str) else '|'.join(f'{t}={p or ''}' for t, p in v.items())}\n")

# spaCy's English tokenizer rules, with Python-only regex syntax rewritten for java.util.regex
def java(p):
    p = p.replace("(?u)", "")
    return re.sub(r"\\U([0-9a-fA-F]{8})", lambda m: "\\x{%X}" % int(m.group(1), 16), p)
t = spacy.load("en_core_web_sm", enable=["tok2vec", "tagger"]).tokenizer
json.dump({
    "prefix": java(t.prefix_search.__self__.pattern),
    "suffix": java(t.suffix_search.__self__.pattern),
    "infix": java(t.infix_finditer.__self__.pattern),
    "url": java(t.url_match.__self__.pattern),
    "specials": {k: [x[65] for x in v] for k, v in t.rules.items() if k.strip()},
}, open(OUT / "en_tokenizer.json", "w", encoding="utf-8"), ensure_ascii=False)

# tagger -> line format: "C<TAB>classes...", "T<TAB>word<TAB>tag", "W<TAB>feature<TAB>classIdx:weight ..."
m = json.load(open("pos_tagger.json", encoding="utf-8"))
idx = {c: i for i, c in enumerate(m["classes"])}
with open(OUT / "pos_tagger.txt", "w", encoding="utf-8") as f:
    f.write("C\t" + "\t".join(m["classes"]) + "\n")
    for w, tg in m["tagdict"].items(): f.write(f"T\t{w}\t{tg}\n")
    for feat, ws in m["weights"].items():
        assert "\t" not in feat and "\n" not in feat
        f.write(f"W\t{feat}\t" + " ".join(f"{idx[c]}:{v:g}" for c, v in ws.items()) + "\n")
print("wrote", sorted(p.name for p in OUT.iterdir()))

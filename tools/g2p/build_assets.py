"""Regenerate app/src/main/assets/g2p/ (the on-device port of misaki's English G2P).

Needs a Python env with misaki and spaCy en_core_web_sm. Steps:
  1. Download UD_English-EWT and UD_English-GUM .conllu files here, run spacy_tag.py (spaCy tags = training target).
  2. python train_tagger.py   -> pos_tagger.json (averaged perceptron imitating spaCy's tagger)
  3. python build_assets.py   -> converts misaki's dictionaries, spaCy's tokenizer rules and the tagger
"""
import json, re, struct
from pathlib import Path
import misaki.data, spacy
from misaki.en import Lexicon

OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/g2p"
OUT.mkdir(parents=True, exist_ok=True)

def write_kv(path, items):
    """PackedTable.kt format, big-endian: "HKV1", count, record offsets, then records
    (u16 key length, UTF-8 key, u16 value length, value), sorted by UTF-8 key bytes."""
    recs = sorted((k.encode("utf-8"), v) for k, v in items.items())
    head = 8 + 4 * len(recs)
    offs, body = [], bytearray()
    for k, v in recs:
        offs.append(head + len(body))
        body += struct.pack(">H", len(k)) + k + struct.pack(">H", len(v)) + v
    path.write_bytes(b"HKV1" + struct.pack(f">I{len(offs)}I", len(offs), *offs) + body)

# misaki dictionaries, grown exactly as Lexicon does -> "phonemes" or "TAG=ps|TAG=ps" (empty ps = no pronunciation)
for name in ("gb_gold", "gb_silver", "us_gold", "us_silver"):
    d = Lexicon.grow_dictionary(json.loads((Path(misaki.data.__file__).parent / f"{name}.json").read_text(encoding="utf-8")))
    write_kv(OUT / f"{name}.kv", {
        k: (v if isinstance(v, str) else "|".join(f"{t}={p or ''}" for t, p in v.items())).encode("utf-8")
        for k, v in d.items()})

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

# tagger: "\tC" -> classes (tab-joined), "\tT\t<word>" -> tag, feature -> (u8 class index, f32 weight)*
m = json.load(open("pos_tagger.json", encoding="utf-8"))
idx = {c: i for i, c in enumerate(m["classes"])}
assert len(idx) < 256 and not any(f.startswith("\t") for f in m["weights"])
items = {"\tC": "\t".join(m["classes"]).encode("utf-8")}
items.update({f"\tT\t{w}": tg.encode("utf-8") for w, tg in m["tagdict"].items()})
items.update({f: b"".join(struct.pack(">Bf", idx[c], v) for c, v in ws.items()) for f, ws in m["weights"].items()})
write_kv(OUT / "pos_tagger.kv", items)
print("wrote", sorted(p.name for p in OUT.iterdir()))

import json, sys, glob, spacy
nlp = spacy.load("en_core_web_sm", enable=["tok2vec", "tagger"])  # same as misaki G2P(trf=False)
for split in ["train", "dev", "test"]:
    texts = []
    for f in sorted(glob.glob(f"*-{split}.conllu")) + sorted(glob.glob(f"*ud-{split}.conllu")):
        texts += [l[9:].strip() for l in open(f, encoding="utf-8") if l.startswith("# text = ")]
    texts = list(dict.fromkeys(texts))
    with open(f"spacy-{split}.jsonl", "w", encoding="utf-8") as out:
        for doc in nlp.pipe(texts, batch_size=256):
            out.write(json.dumps({"text": doc.text, "toks": [[t.text, t.tag_, t.whitespace_] for t in doc]}, ensure_ascii=False) + "\n")
    print(split, len(texts))

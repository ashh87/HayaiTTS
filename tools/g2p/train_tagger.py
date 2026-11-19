"""Averaged perceptron POS tagger distilled from spaCy en_core_web_sm tags. Feature code is mirrored in Kotlin."""
import json, random, sys
from collections import defaultdict, Counter

def norm(w):
    if "-" in w and w[0] != "-": return "!HYPHEN"
    if w.isdigit() and len(w) == 4: return "!YEAR"
    if w and w[0].isdigit(): return "!DIGITS"
    return w.lower()

def shape(w):
    s = "".join("X" if c.isupper() else "x" if c.islower() else "d" if c.isdigit() else c for c in w)
    out = []
    for c in s:
        if len(out) >= 2 and out[-1] == c and out[-2] == c: continue
        out.append(c)
    return "".join(out)[:6]

def features(i, words, ctx, prev, prev2):
    w = words[i]; i += 2
    f = ["b", "s3 " + ctx[i][-3:], "s2 " + ctx[i][-2:], "s1 " + ctx[i][-1:], "p1 " + w[0], "sh " + shape(w),
         "t1 " + prev, "t2 " + prev2, "t12 " + prev + " " + prev2, "w " + ctx[i], "t1w " + prev + " " + ctx[i],
         "w-1 " + ctx[i-1], "s-1 " + ctx[i-1][-3:], "w-2 " + ctx[i-2], "w+1 " + ctx[i+1], "s+1 " + ctx[i+1][-3:],
         "w+2 " + ctx[i+2], "sh+1 " + (shape(words[i-1]) if i - 1 < len(words) else "END")]
    return f

class Perceptron:
    def __init__(self):
        self.w = defaultdict(dict); self.classes = set(); self.tagdict = {}
        self._tot = defaultdict(int); self._ts = defaultdict(int); self.i = 0
    def predict(self, feats):
        scores = defaultdict(float)
        for f in feats:
            for c, v in self.w.get(f, {}).items(): scores[c] += v
        return max(self.classes, key=lambda c: (scores[c], c))
    def update(self, truth, guess, feats):
        self.i += 1
        if truth == guess: return
        for f in feats:
            ws = self.w[f]
            for c, d in ((truth, 1.0), (guess, -1.0)):
                p = (f, c); cur = ws.get(c, 0.0)
                self._tot[p] += (self.i - self._ts[p]) * cur; self._ts[p] = self.i
                ws[c] = cur + d
    def average(self):
        for f, ws in self.w.items():
            for c in list(ws):
                p = (f, c); tot = self._tot[p] + (self.i - self._ts[p]) * ws[c]
                v = round(tot / self.i, 3)
                if abs(v) >= 0.002: ws[c] = v
                else: del ws[c]

START, END = ["-S-", "-S2-"], ["-E-", "-E2-"]
def tag(model, words):
    ctx = START + [norm(w) for w in words] + END
    prev, prev2, out = "-S-", "-S2-", []
    for i, w in enumerate(words):
        t = model.tagdict.get(w)
        if t is None:
            t = model.predict(features(i, words, ctx, prev, prev2))
        out.append(t); prev2, prev = prev, t
    return out

def load(split):
    return [([t[0] for t in d["toks"]], [t[1] for t in d["toks"]]) for d in map(json.loads, open(f"spacy-{split}.jsonl", encoding="utf-8"))]

if __name__ == "__main__":
    train, test = load("train") + load("dev"), load("test")
    m = Perceptron()
    counts = defaultdict(Counter)
    for ws, ts in train:
        for w, t in zip(ws, ts): counts[w][t] += 1; m.classes.add(t)
    m.tagdict = {w: c.most_common(1)[0][0] for w, c in counts.items() if sum(c.values()) >= 20 and c.most_common(1)[0][1] / sum(c.values()) >= 0.99}
    for it in range(8):
        random.seed(it); random.shuffle(train); right = tot = 0
        for ws, ts in train:
            ctx = START + [norm(w) for w in ws] + END; prev, prev2 = "-S-", "-S2-"
            for i, w in enumerate(ws):
                g = m.tagdict.get(w)
                if g is None:
                    f = features(i, ws, ctx, prev, prev2); g = m.predict(f); m.update(ts[i], g, f)
                prev2, prev = prev, g; right += g == ts[i]; tot += 1
        print("iter", it, right / tot, flush=True)
    m.average()
    right = tot = 0
    for ws, ts in test:
        g = tag(m, ws); right += sum(a == b for a, b in zip(g, ts)); tot += len(ts)
    print("test agreement with spaCy:", right / tot)
    json.dump({"classes": sorted(m.classes), "tagdict": m.tagdict, "weights": {f: ws for f, ws in m.w.items() if ws}},
              open("pos_tagger.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

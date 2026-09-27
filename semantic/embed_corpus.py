# -*- coding: utf-8 -*-
"""يحسب متجهات المتون بنموذج bge-m3 (ONNX int8 من حزمة model_bge) بمقطّع التطبيق نفسه، ويكتبها بصيغة MSBV v2 للهاتف.
المتن يُجرَّد من التشكيل كما في ArabicText.stripDiacritics لأن المستخدم يكتب استعلامه بلا تشكيل
(متجهات المتون المشكولة المنقولة من سطح المكتب لا تطابق الاستعلام غير المشكول).
python3 embed_corpus.py --db muhaddith_corpus_tisa.db --dataset tisa --model DIR --out muhaddith_vectors_tisa.bin --work DIR
DIR فيه model.onnx وvocab.bin وmodel.json (محتوى حزمة model_bge). يُستأنف من حيث توقّف: كل شريحة تُحفظ في --work.
"""
import argparse, sqlite3, zlib, re, struct, time, os, json, unicodedata
from functools import lru_cache
import numpy as np, onnxruntime as ort

DIAC = re.compile("[\u0610-\u061a\u064b-\u065f\u0670\u06d6-\u06ed\u0640]")   # = ArabicText.DIACRITICS
META = "\u2581"
SHARD = 2048

class Tok:
    """مطابق لـ UnigramTokenizer.kt (NFKC، ▁، Viterbi بدرجات float32)"""
    def __init__(self, path):
        b = open(path, "rb").read(); assert b[:4] == b"MSBT"
        n = struct.unpack_from("<I", b, 4)[0]; o = 8; self.vocab, self.ids = {}, {}
        for i in range(n):
            l = struct.unpack_from("<H", b, o)[0]; o += 2
            p = b[o:o+l].decode("utf-8"); o += l
            self.vocab[p] = struct.unpack_from("<f", b, o)[0]; self.ids[p] = i; o += 4
        self.unk = np.float32(min(self.vocab.values()) - 10.0)
        self.maxlen = min(max(len(k) for k in self.vocab), 32)
        self.piece_ids = lru_cache(maxsize=1 << 20)(self._piece_ids)

    def _piece_ids(self, w):
        n = len(w); NEG = np.float32("-inf")
        best = [NEG] * (n + 1); best[0] = np.float32(0); back = [-1] * (n + 1); unk = [False] * (n + 1)
        for i in range(n):
            if best[i] == NEG: continue
            for j in range(i + 1, min(n, i + self.maxlen) + 1):
                sc = self.vocab.get(w[i:j])
                if sc is not None:
                    c = best[i] + np.float32(sc)
                    if c > best[j]: best[j] = c; back[j] = i; unk[j] = False
            c = best[i] + self.unk
            if c > best[i + 1]: best[i + 1] = c; back[i + 1] = i; unk[i + 1] = True
        out = []; k = n
        while k > 0:
            s = back[k]; out.append(3 if unk[k] else self.ids[w[s:k]]); k = s
        return out[::-1]

    def encode(self, text, max_len):
        if not text: return [0, 2]
        s = unicodedata.normalize("NFKC", text)
        s = re.sub(" {2,}", " ", s).replace(" ", META)
        if not s.startswith(META): s = META + s
        cuts = [i for i, ch in enumerate(s) if ch == META] + [len(s)]
        out = []
        for a, b in zip(cuts, cuts[1:]):
            out += self.piece_ids(s[a:b])
            if len(out) >= max_len - 2: break
        return [0] + out[:max_len - 2] + [2]

def write_msbv(path, dataset, ids, scales, vecs):
    o = np.argsort(ids, kind="stable"); ids, scales, vecs = ids[o], scales[o], vecs[o]
    with open(path + ".tmp", "wb") as f:
        f.write(b"MSBV"); f.write(struct.pack("<III", 2, vecs.shape[1], len(ids))); f.write(dataset.encode().ljust(16, b"\0"))
        f.write(ids.astype(np.int32).tobytes()); f.write(scales.astype(np.float32).tobytes()); f.write(vecs.astype(np.int8).tobytes())
    os.replace(path + ".tmp", path)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True); ap.add_argument("--out", required=True); ap.add_argument("--dataset", required=True, choices=["tisa", "full"])
    ap.add_argument("--model", required=True); ap.add_argument("--work", required=True)
    ap.add_argument("--maxlen", type=int, default=512); ap.add_argument("--tokens", type=int, default=2048, help="رموز الدفعة الواحدة؛ 0 = متن واحد في كل تشغيل كالتطبيق تمامًا")
    ap.add_argument("--threads", type=int, default=os.cpu_count()); ap.add_argument("--limit", type=int, default=None)
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    cfg = json.load(open(os.path.join(a.model, "model.json")))
    tok = Tok(os.path.join(a.model, "vocab.bin"))
    so = ort.SessionOptions(); so.intra_op_num_threads = a.threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(os.path.join(a.model, "model.onnx"), so, providers=["CPUExecutionProvider"])
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    rows = [(h, DIAC.sub("", zlib.decompress(m).decode("utf-8")).strip()) for h, m in db.execute("SELECT id, matn FROM hadiths ORDER BY id LIMIT ?", (a.limit or -1,))]
    # ترتيب حسب الطول لتقليل الحشو، ثم الشرائح بترتيب عشوائي ثابت ليكون المعدّل والوقت الباقي واقعيَّين
    rows.sort(key=lambda r: (len(r[1]), r[0]))
    shards = [rows[i:i + SHARD] for i in range(0, len(rows), SHARD)]
    # الشرائح المحفوظة تُستأنف فقط إن صنعتها المدخلات نفسها (وإلا اختلطت متجهات نص/نموذج آخر بمعرّفات هذا المتن)
    params = {"db": [os.path.getsize(a.db), int(os.path.getmtime(a.db))], "model": os.path.getsize(os.path.join(a.model, "model.onnx")),
              "maxlen": a.maxlen, "tokens": a.tokens, "limit": a.limit, "shard": SHARD, "rows": len(rows)}
    pf = os.path.join(a.work, "params.json")
    if os.path.exists(pf): assert json.load(open(pf)) == params, f"{a.work} holds shards from other inputs: {pf}"
    else: json.dump(params, open(pf, "w"))
    todo = [s for s in np.random.default_rng(0).permutation(len(shards)) if not os.path.exists(os.path.join(a.work, f"{s:05d}.npz"))]
    print(f"{a.dataset}: {len(rows)} docs, {len(shards)} shards, {len(todo)} to do, maxlen {a.maxlen}, no prefix", flush=True)
    t0 = time.time(); done = 0; last = 0
    for s in todo:
        chunk = shards[s]
        enc = [tok.encode(t, a.maxlen) for _, t in chunk]
        V = np.zeros((len(chunk), cfg["dim"]), np.float32); i = 0
        while i < len(chunk):
            L = len(enc[i]); j = i + 1
            while j < len(chunk) and (j + 1 - i) * max(L, len(enc[j])) <= a.tokens: L = max(L, len(enc[j])); j += 1
            ids = np.full((j - i, L), 1, np.int64); mask = np.zeros_like(ids)   # 1 = <pad>
            for r, e in enumerate(enc[i:j]): ids[r, :len(e)] = e; mask[r, :len(e)] = 1
            # CLS مُعيَّر (export_bge.py). ponytail: التكميم الديناميكي يحسب مقياس التنشيط على الدفعة كلها فيبعد المتجه قليلًا
            # عن تشغيله منفردًا (جيب تمام ≈ ٠٫٩٨)؛ قيس على البخاري فلم يغيّر الاسترجاع وهو أسرع ١٫٨×؛ --tokens 0 للتطابق التام
            V[i:j] = sess.run(None, {"input_ids": ids, "attention_mask": mask})[0]
            i = j
        sc = np.abs(V).max(axis=1); sc[sc == 0] = 1.0
        q = np.clip(np.round(V / sc[:, None] * 127.0), -127, 127).astype(np.int8)
        np.savez(os.path.join(a.work, f"{s:05d}.tmp.npz"), ids=np.array([h for h, _ in chunk], np.int32), scales=(sc / 127.0).astype(np.float32), vecs=q)
        os.replace(os.path.join(a.work, f"{s:05d}.tmp.npz"), os.path.join(a.work, f"{s:05d}.npz"))
        done += len(chunk)
        if time.time() - last > 180 or s == todo[-1]:
            last = time.time(); rate = done / (last - t0); left = sum(len(shards[x]) for x in todo) - done
            print(f"  {time.strftime('%H:%M:%S')} {done} docs, {rate:.1f} docs/s, eta {left / rate / 3600:.1f} h", flush=True)
    Z = [np.load(os.path.join(a.work, f"{s:05d}.npz")) for s in range(len(shards))]
    ids = np.concatenate([z["ids"] for z in Z]); assert len(ids) == len(rows) and set(ids.tolist()) == {h for h, _ in rows}
    write_msbv(a.out, a.dataset, ids, np.concatenate([z["scales"] for z in Z]), np.concatenate([z["vecs"] for z in Z]))
    print(f"wrote {a.out}: {len(ids)} vectors, {os.path.getsize(a.out) / 1e6:.1f} MB, {(time.time() - t0) / 60:.1f} min", flush=True)

if __name__ == "__main__":
    main()

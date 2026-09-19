# -*- coding: utf-8 -*-
"""يحسب متجهات المتون بنموذج multilingual-e5-small (ONNX int8) ويكتبها بصيغة مدمجة للهاتف.
python3 embed_corpus.py --db muhaddith_corpus_full.db --out vectors_full.bin [--max-priority 8]
"""
import argparse, sqlite3, zlib, re, struct, time, sys, os
import numpy as np, onnxruntime as ort
from transformers import AutoTokenizer

DIAC = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")

def prep(matn):
    return "passage: " + DIAC.sub("", matn).strip()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--max-priority", type=int, default=None); ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--maxlen", type=int, default=128)
    a = ap.parse_args()
    tok = AutoTokenizer.from_pretrained("e5-tok")
    so = ort.SessionOptions(); so.intra_op_num_threads = 2
    sess = ort.InferenceSession("onnx/e5_int8.onnx", so)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    q = "SELECT h.id, h.matn, c.taraf FROM hadiths h JOIN books b ON b.id=h.book_id LEFT JOIN clusters c ON c.id=h.cluster_id"
    if a.max_priority is not None: q += f" WHERE b.priority <= {a.max_priority}"
    q += " ORDER BY h.id"
    raw = db.execute(q).fetchall()
    # النص المضمَّن: طرف الحديث المجمَّع (خلاصته) ثم المتن — يقوّي المطابقة بالمعنى ويقلّل انحياز القِصر
    def full_text(m, taraf):
        matn = DIAC.sub("", zlib.decompress(m).decode("utf-8")).strip()
        t = DIAC.sub("", (taraf or "")).strip()
        return "passage: " + (t + " — " + matn if t and t not in matn else matn)
    texts_all = [full_text(m, t) for _, m, t in raw]
    # ترتيب حسب الطول لتقليل الحشو داخل الدفعة (أسرع بكثير)
    order = sorted(range(len(raw)), key=lambda i: len(texts_all[i]))
    rows = [(raw[i][0], texts_all[i]) for i in order]
    n = len(rows); dim = 384
    print(f"docs: {n}", flush=True)
    ids = np.zeros(n, dtype=np.int32); scales = np.zeros(n, dtype=np.float32); vecs = np.zeros((n, dim), dtype=np.int8)
    t0 = time.time()
    for i in range(0, n, a.batch):
        chunk = rows[i:i+a.batch]
        texts = [t for _, t in chunk]
        e = tok(texts, return_tensors="np", padding=True, truncation=True, max_length=a.maxlen)
        out = sess.run(None, {"input_ids": e["input_ids"].astype(np.int64), "attention_mask": e["attention_mask"].astype(np.int64)})[0]
        for j, (hid, _) in enumerate(chunk):
            v = out[j]; s = float(np.abs(v).max()) or 1.0
            ids[i+j] = hid; scales[i+j] = s / 127.0
            vecs[i+j] = np.clip(np.round(v / s * 127.0), -127, 127).astype(np.int8)
        if (i // a.batch) % 50 == 0:
            done = i + len(chunk); rate = done / (time.time() - t0)
            print(f"  {done}/{n}  {rate:.0f} docs/s  eta {((n-done)/max(rate,1e-6))/60:.0f} min", flush=True)
    o = np.argsort(ids, kind="stable"); ids, scales, vecs = ids[o], scales[o], vecs[o]
    with open(a.out, "wb") as f:
        f.write(b"MSBV"); f.write(struct.pack("<III", 1, dim, n))
        f.write(ids.tobytes()); f.write(scales.tobytes()); f.write(vecs.tobytes())
    print(f"wrote {a.out} {os.path.getsize(a.out)/1e6:.1f} MB in {(time.time()-t0)/60:.1f} min", flush=True)

if __name__ == "__main__":
    main()

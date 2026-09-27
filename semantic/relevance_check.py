# -*- coding: utf-8 -*-
"""فحص صلاحية متجهات البحث الدلالي بمسار استعلام التطبيق نفسه (المقطّع، query_prefix، max_len، الضرب في int8×المقياس).
python3 relevance_check.py --model DIR --db muhaddith_corpus_tisa.db --vectors muhaddith_vectors_tisa.bin
يفشل (رمز خروج 1) إن لم يبلغ: ٧٠٪ من الاستعلامات المعروفة في العشرة الأوائل، و٧٠٪ من استعلامات «أول أربع كلمات» على مجموعة البخاري في العشرة الأوائل (نتيجة الفهرس كله للاطلاع فقط).
"""
import argparse, json, os, sqlite3, struct, sys, zlib
import numpy as np, onnxruntime as ort
from embed_corpus import DIAC, Tok

# (الاستعلام كما يكتبه المستخدم، عبارة يكفي وجودها في المتن المجرَّد من التشكيل ليكون ذا صلة)
KNOWN = [
    ("إنما الأعمال بالنيات", "الأعمال بالنيات"),
    ("غض البصر", "أغض للبصر"),
    ("من غشنا فليس منا", "من غش"),
    ("الدين النصيحة", "الدين النصيحة"),
    ("لا ضرر ولا ضرار", "لا ضرر ولا ضرار"),
    ("الطهور شطر الإيمان", "شطر الإيمان"),
    ("من كان يؤمن بالله واليوم الآخر فليقل خيرا أو ليصمت", "فليقل خيرا أو ليصمت"),
    ("بني الإسلام على خمس", "بني الإسلام على خمس"),
    # بالمعنى لا باللفظ
    ("النية شرط قبول العمل", "بالنيات"),
    ("الابتسامة في وجه الأخ صدقة", "تبسمك في وجه أخيك"),
    ("وصية النبي بترك الغضب", "لا تغضب"),
]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--db", required=True); ap.add_argument("--vectors", required=True)
    ap.add_argument("--show", type=int, default=3, help="أعلى النتائج المطبوعة لأول ثلاثة استعلامات")
    a = ap.parse_args()
    cfg = json.load(open(os.path.join(a.model, "model.json"))); tok = Tok(os.path.join(a.model, "vocab.bin"))
    sess = ort.InferenceSession(os.path.join(a.model, "model.onnx"), providers=["CPUExecutionProvider"])
    b = open(a.vectors, "rb").read(); assert b[:4] == b"MSBV"
    ver, dim, n = struct.unpack_from("<III", b, 4); o = 32 if ver >= 2 else 16
    ids = np.frombuffer(b, np.int32, n, o); sc = np.frombuffer(b, np.float32, n, o + 4 * n)
    V = np.frombuffer(b, np.int8, n * dim, o + 8 * n).reshape(n, dim)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    rows = db.execute("SELECT id, book_id, hadith_num, matn FROM hadiths").fetchall()
    plain = {h: DIAC.sub("", zlib.decompress(m).decode("utf-8")) for h, _, _, m in rows}
    book = {h: (bk, num) for h, bk, num, _ in rows}
    text = [plain[int(h)] for h in ids]

    def scores(q):   # = SemanticEngine.embedQuery + VectorIndex.search
        e = np.array([tok.encode(cfg.get("query_prefix", "") + q.strip(), cfg.get("max_len", 64))], np.int64)
        qv = sess.run(None, {"input_ids": e, "attention_mask": np.ones_like(e)})[0][0]
        return (V @ qv) * sc

    def best_rank(s, rel):   # أفضل رتبة (من الصفر) لمتن ذي صلة
        return int((s > s[rel].max()).sum()) if rel.any() else None

    def report(label, ranks):
        r = np.array([x for x in ranks if x is not None])
        print(f"{label}: n={len(r)} top1={np.mean(r < 1):.2f} top10={np.mean(r < 10):.2f} median rank={np.median(r):.0f}")
        return np.mean(r < 10)

    kr = []
    for qi, (q, key) in enumerate(KNOWN):
        s = scores(q); rel = np.array([key in t for t in text]); r = best_rank(s, rel); kr.append(r)
        print(f"«{q}»  relevant={int(rel.sum())}  best rank={r}")
        if qi < 3:
            for i in np.argsort(-s)[:a.show]:
                print(f"     {s[i]:.4f} id={int(ids[i])} book={book[int(ids[i])][0]} #{book[int(ids[i])][1]}: {text[i][:90]}")
    k10 = report("known queries", kr)
    # بروتوكول compare.py: أول أربع كلمات من ١٠٠ متن عشوائي من البخاري (١٤٦)، الصلة = احتواء العبارة
    bh = np.array(sorted(h for h, (bk, _) in book.items() if bk == 146)); inb = np.isin(ids, bh)
    wr, wr_book = [], []
    for h in np.random.default_rng(1).choice(bh, 100, replace=False) if len(bh) else []:
        w = plain[int(h)].split()
        if len(w) < 6: continue
        ph = " ".join(w[:4]); s = scores(ph); rel = np.array([ph in t for t in text])
        wr.append(best_rank(s, rel)); wr_book.append(best_rank(np.where(inb, s, -9), rel & inb))
    # البوابة على مقياسين لا يتأثران بحجم الفهرس (مجموعة البخاري تبقى هي نفسها في التسعة والكاملة)؛ الفهرس كله للاطلاع فقط
    b10 = report("first-4-words, Bukhari subset (as compare.py)", wr_book) if wr else 0.0   # بلا البخاري (١٤٦) لا تمرّ البوابة
    if wr: report("first-4-words, whole index (info)", wr)
    ok = k10 >= 0.7 and b10 >= 0.7
    print("PASS" if ok else "FAIL"); sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()

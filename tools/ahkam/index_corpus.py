# -*- coding: utf-8 -*-
"""
يبني فهرس شظايا (4 كلمات) لمتون أحاديث clusters.db كلها، ليُطابَق به أي نص خارجي
(أحكام مأخوذة من الشبكة، أو مقاطع كتب مختلف الحديث) مع الحديث/العنقود المناسب.

  python3 index_corpus.py --src clusters.db --out work/

المخرجات:
  work/hadiths.tsv   : idx \t bhid \t book_id \t cluster_id \t hadith_num \t page_num \t nwords
  work/sh_hash.npy   : int64 مرتب
  work/sh_hid.npy    : int32 رقم الحديث (idx) لكل شظية
"""
import argparse, os, sqlite3, sys, time
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from common import content_words, shingles

N = 4
MAX_WORDS = 600

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--exclude-cats", default="4,5,13", help="أصناف كتب لا تدخل الفهرس (تاريخ، تفسير، فقه وشروح) لأن مداخلها الطويلة تفسد الربط")
    a = ap.parse_args()
    excl = set(x for x in a.exclude_cats.split(",") if x)
    os.makedirs(a.out, exist_ok=True)
    c = sqlite3.connect(a.src)
    t0 = time.time()
    # كل نصوص الأحاديث (مع ما ليس له صف في hadiths: كتب العلل ونحوها) — نأخذ block_type 2/3/8
    q = """SELECT t.bhid, t.hadith_num, t.page_num, t.clean_matn, t.text, h.book_id, h.cluster_id
           FROM hadith_texts t LEFT JOIN hadiths h ON h.bhid = t.bhid
           WHERE t.block_type IN (2,3,8)"""
    cat = dict(c.execute("select book_id, category_id from books").fetchall())
    hashes, hids = [], []
    meta = open(os.path.join(a.out, "hadiths.tsv"), "w", encoding="utf-8")
    idx = 0
    seen = set()
    for bhid, num, page, matn, text, book_id, cid in c.execute(q):
        if bhid in seen:
            continue
        seen.add(bhid)
        if not book_id:
            book_id = bhid.split("_")[0]
        if str(cat.get(book_id)) in excl:
            continue
        src = matn or text or ""
        ws = content_words(src)
        if len(ws) > MAX_WORDS:
            ws = ws[:MAX_WORDS]   # نصوص عملاقة (كتب كاملة أُدخلت حديثًا واحدًا) تفسد المطابقة
        if len(ws) < N:
            # متن قصير جدًّا: نستعمل النص كله
            ws = content_words(text or "")
        sh = shingles(ws, N)
        for hsh, _ in sh:
            hashes.append(hsh)
            hids.append(idx)
        meta.write(f"{idx}\t{bhid}\t{book_id}\t{cid or ''}\t{num or ''}\t{page or ''}\t{len(ws)}\n")
        idx += 1
        if idx % 50000 == 0:
            print(f"  {idx} نص… {len(hashes)} شظية {time.time()-t0:.0f}s", flush=True)
    meta.close()
    print("ترتيب…", flush=True)
    H = np.array(hashes, dtype=np.int64)
    I = np.array(hids, dtype=np.int32)
    del hashes, hids
    order = np.argsort(H, kind="stable")
    H = H[order]; I = I[order]
    np.save(os.path.join(a.out, "sh_hash.npy"), H)
    np.save(os.path.join(a.out, "sh_hid.npy"), I)
    print(f"تم: {idx} نص، {len(H)} شظية، {time.time()-t0:.0f}s")

if __name__ == "__main__":
    main()

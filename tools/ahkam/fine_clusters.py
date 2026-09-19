# -*- coding: utf-8 -*-
"""تقسيم العناقيد الضخمة في المتن إلى «عناقيد دقيقة» بحسب تشابه المتن (شظايا ٤ كلمات)

عناقيد المحدِّث الأصلية تجمع أحيانًا مئات الأحاديث المختلفة في عنقود واحد (بحسب الأسانيد والأبواب)، فإذا رُبط الشرح
بالعنقود ظهر شرح حديث آخر تحت الحديث. هنا نقسّم كل عنقود فيه أكثر من MIN_BIG رواية إلى مجموعات بتشابه المتن:
رواية ← مجموعة إن تشاركتا ≥ MIN_SHARED شظية وكان الاحتواء (المشترك ÷ الأصغر) ≥ MIN_CONT (اتحاد متعدٍّ).

  python3 fine_clusters.py --corpus muhaddith_corpus_full.db --out work/fine.tsv
الناتج: hadith_id \t fine_id لكل رواية في عنقود ضخم (fine_id = أصغر معرّف في المجموعة). الروايات في العناقيد الصغيرة لا تُذكر (عنقودها هو مجموعتها).
"""
import argparse, os, sqlite3, sys, time, zlib
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from common import content_words, shingles, strip_diac

MIN_BIG, MIN_SHARED, MIN_CONT, MAX_DF = 40, 3, 0.35, 300


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--corpus", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args(); t0 = time.time()
    k = sqlite3.connect(a.corpus)
    big = [c for (c,) in k.execute(f"select cluster_id from hadiths where cluster_id is not null group by cluster_id having count(*) > {MIN_BIG}")]
    out = open(a.out, "w", encoding="utf-8"); nrows = ngroups = 0
    for ci, cl in enumerate(big):
        rows = k.execute("select id, matn from hadiths where cluster_id=?", (cl,)).fetchall()
        sh = {}
        for hid, blob in rows:
            try:
                ws = content_words(strip_diac(zlib.decompress(blob).decode("utf-8")))
            except Exception:
                continue
            sh[hid] = set(h for h, _ in shingles(ws, 4))
        post = defaultdict(list)
        for hid, s in sh.items():
            for h in s:
                post[h].append(hid)
        parent = {hid: hid for hid in sh}
        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        shared = defaultdict(int)
        for h, ids in post.items():
            if len(ids) > MAX_DF or len(ids) < 2:
                continue
            for i in range(len(ids)):
                for j in range(i + 1, len(ids)):
                    x, y = ids[i], ids[j]
                    shared[(x, y) if x < y else (y, x)] += 1
        for (x, y), n in shared.items():
            if n >= MIN_SHARED and n / max(1, min(len(sh[x]), len(sh[y]))) >= MIN_CONT:
                rx, ry = find(x), find(y)
                if rx != ry:
                    parent[max(rx, ry)] = min(rx, ry)
        groups = defaultdict(list)
        for hid in sh:
            groups[find(hid)].append(hid)
        for root, members in groups.items():
            fid = min(members)
            for hid in members:
                out.write(f"{hid}\t{fid}\n"); nrows += 1
        ngroups += len(groups)
        if ci % 200 == 0:
            print(f"{ci}/{len(big)} عنقود — {time.time()-t0:.0f}s", flush=True)
    out.close()
    print(f"تم: {len(big)} عنقود ضخم → {ngroups} مجموعة دقيقة، {nrows} رواية — {time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()

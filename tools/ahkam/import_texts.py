# -*- coding: utf-8 -*-
"""
يستورد كتب مختلف الحديث والناسخ والمنسوخ والتخريج والعلل من نصوص OpenITI، ويقسمها مقاطع،
ويربط كل مقطع بالأحاديث/العناقيد التي اقتبسها، ويستخرج أحكام المؤلف داخل المقاطع.

  python3 import_texts.py --data openiti/data --work work --out work/texts.sqlite
"""
import argparse, glob, json, os, re, sqlite3, sys, time, zlib
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from openiti import parse, read_meta
from matcher import CorpusIndex
from common import content_words, strip_diac, grade_level
from rulings import find_rulings

# (معرّف OpenITI، العنوان المعروض، المؤلف المشهور، الوفاة، النوع، ترتيب العرض)
BOOKS = [
    ("0204Shafici.IkhtilafHadith", "اختلاف الحديث", "الشافعي", 204, "mukhtalif", 1),
    ("0276IbnQutaybaDinawari.TawilMukhtalafHadith", "تأويل مختلف الحديث", "ابن قتيبة", 276, "mukhtalif", 2),
    ("0321Tahawi.SharhMushkilAthar", "شرح مشكل الآثار", "الطحاوي", 321, "mukhtalif", 3),
    ("0321Tahawi.SharhMacaniAthar", "شرح معاني الآثار", "الطحاوي", 321, "mukhtalif", 4),
    ("0406IbnFurakIsbahani.MushkilHadith", "مشكل الحديث وبيانه", "ابن فورك", 406, "mukhtalif", 5),
    ("0597IbnJawzi.KashfMushkil", "كشف المشكل من حديث الصحيحين", "ابن الجوزي", 597, "mukhtalif", 6),
    ("0273AbuBakrAthram.NasikhHadith", "ناسخ الحديث ومنسوخه", "الأثرم", 273, "nasikh", 10),
    ("0385IbnShahin.NasikhHadithWaMansukhuh", "ناسخ الحديث ومنسوخه", "ابن شاهين", 385, "nasikh", 11),
    ("0584IbnMusaHazimi.IctibarFiNasikhWaMansukh", "الاعتبار في الناسخ والمنسوخ من الآثار", "الحازمي", 584, "nasikh", 12),
    ("0643IbnSalahShahrazuri.MuqaddimatCulumHadith", "معرفة أنواع علوم الحديث (مقدمة ابن الصلاح)", "ابن الصلاح", 643, "usul", 20),
    ("0852IbnHajarCasqalani.NukatCalaIbnSalah", "النكت على كتاب ابن الصلاح", "ابن حجر", 852, "usul", 21),
    ("0852IbnHajarCasqalani.NuzhatNazar", "نزهة النظر في توضيح نخبة الفكر", "ابن حجر", 852, "usul", 22),
    ("0643IbnSalahShahrazuri.SiyanatSahihMuslim", "صيانة صحيح مسلم", "ابن الصلاح", 643, "usul", 23),
    ("0852IbnHajarCasqalani.TalkhisHabir", "التلخيص الحبير", "ابن حجر", 852, "takhrij", 30),
    ("0852IbnHajarCasqalani.DirayaFiTakhrijAhadithHidaya", "الدراية في تخريج أحاديث الهداية", "ابن حجر", 852, "takhrij", 31),
    ("0852IbnHajarCasqalani.NataijAfkar", "نتائج الأفكار في تخريج أحاديث الأذكار", "ابن حجر", 852, "takhrij", 32),
    ("0762IbnYusufZaylaci.NasbRaya", "نصب الراية لأحاديث الهداية", "الزيلعي", 762, "takhrij", 33),
    ("0804IbnMulaqqin.BadrMunir", "البدر المنير", "ابن الملقن", 804, "takhrij", 34),
    ("0744IbnCabdHadi.TanqihTahqiq", "تنقيح التحقيق في أحاديث التعليق", "ابن عبد الهادي", 744, "takhrij", 35),
    ("0597IbnJawzi.Mawducat", "الموضوعات", "ابن الجوزي", 597, "ilal", 40),
    ("0597IbnJawzi.CilalMutanahiyya", "العلل المتناهية في الأحاديث الواهية", "ابن الجوزي", 597, "ilal", 41),
    ("0748Dhahabi.TalkhisMawducatIbnJawzi", "تلخيص كتاب الموضوعات لابن الجوزي", "الذهبي", 748, "ilal", 42),
    ("0742Mizzi.TuhfatAshraf", "تحفة الأشراف بمعرفة الأطراف", "المزي", 742, "atraf", 50),
]

def find_file(data, uri):
    author = uri.split(".")[0]
    d = os.path.join(data, author, uri)
    cands = [p for p in glob.glob(os.path.join(d, uri + ".*")) if not p.endswith(".yml") and "README" not in p]
    sham = [p for p in cands if ".Shamela" in p] or [p for p in cands if "Sham" in p] or cands
    return sham[0] if sham else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    ix = CorpusIndex(a.work)
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript("""
      CREATE TABLE mk_books(id INTEGER PRIMARY KEY, uri TEXT, title TEXT, author TEXT, death INTEGER, kind TEXT, ord INTEGER,
                            edition TEXT, editor TEXT, publisher TEXT, vols INTEGER, sections INTEGER, words INTEGER);
      CREATE TABLE mk_sections(id INTEGER PRIMARY KEY, book_id INTEGER, ord INTEGER, title TEXT, vol INTEGER,
                               page_start INTEGER, page_end INTEGER, words INTEGER, text BLOB);
      CREATE TABLE mk_links(section_id INTEGER, cluster_id TEXT, bhid TEXT, hits INTEGER, para INTEGER);
      CREATE TABLE mk_rulings(section_id INTEGER, book_id INTEGER, cluster_id TEXT, scholar TEXT, grade TEXT, level INTEGER, quote TEXT, para INTEGER);
    """)
    t0 = time.time()
    sec_id = 0
    for bi, (uri, title, author, death, kind, ordv) in enumerate(BOOKS, 1):
        if a.only and a.only not in uri:
            continue
        path = find_file(a.data, uri)
        if not path:
            print("!! لم أجد", uri); continue
        meta = read_meta(path)
        secs = parse(path)
        nwords = sum(len(s["text"].split()) for s in secs)
        db.execute("INSERT INTO mk_books VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (bi, uri, title, author, death, kind, ordv, meta.get("041.EdNUMBER"), meta.get("040.EdEDITOR"),
                    meta.get("043.EdPUBLISHER"), int(re.sub(r"\D", "", meta.get("022.BookVOLS", "") or "0") or 0), len(secs), nwords))
        nlinks = nrul = 0
        for s in secs:
            sec_id += 1
            paras = s["text"].split("\n")
            db.execute("INSERT INTO mk_sections VALUES(?,?,?,?,?,?,?,?,?)",
                       (sec_id, bi, s["ord"], s["title"], s["vol"], s["page_start"], s["page_end"],
                        len(s["text"].split()), zlib.compress(s["text"].encode("utf-8"), 9)))
            # ربط الفقرات بالعناقيد
            para_clusters = []   # لكل فقرة: {cluster: hits}
            for pi, p in enumerate(paras):
                ws = content_words(p)
                hits = defaultdict(set); best_idx = {}
                for pos, idx in ix.scan(ws):
                    cl = ix.cluster[idx]
                    if not cl:
                        continue
                    hits[cl].add(pos)
                    best_idx.setdefault(cl, idx)
                # نُبقي العناقيد القوية فقط (٣ شظايا مختلفة فأكثر)؛ ولا نُغرق الفقرة بعشرات العناقيد
                strong = {cl: len(h) for cl, h in hits.items() if len(h) >= 3}
                if len(strong) > 6:
                    top = sorted(strong.items(), key=lambda x: -x[1])[:6]
                    strong = dict(top)
                para_clusters.append(strong)
                for cl, h in strong.items():
                    db.execute("INSERT INTO mk_links VALUES(?,?,?,?,?)", (sec_id, cl, ix.bhid[best_idx[cl]], h, pi))
                    nlinks += 1
            # أحكام المؤلف داخل المقطع (لكتب التخريج والعلل والمختلف)
            if kind in ("takhrij", "ilal", "mukhtalif", "nasikh"):
                for pi, p in enumerate(paras):
                    plain = strip_diac(p)
                    for phrase, speaker, st, en in find_rulings(plain, None):
                        # العنقود: من الفقرة نفسها، وإلا من أقرب فقرة سابقة (حتى 3 فقرات)
                        target = None
                        for back in range(0, 4):
                            j = pi - back
                            if j < 0:
                                break
                            if para_clusters[j]:
                                target = max(para_clusters[j].items(), key=lambda x: x[1])[0]
                                break
                        if target is None:
                            continue
                        quote = plain[max(0, st - 110):min(len(plain), en + 40)]
                        db.execute("INSERT INTO mk_rulings VALUES(?,?,?,?,?,?,?,?)",
                                   (sec_id, bi, target, speaker or author, phrase, grade_level(phrase), "…" + quote.strip() + "…", pi))
                        nrul += 1
        db.commit()
        print(f"{title}: {len(secs)} مقطع، {nwords} كلمة، {nlinks} رابط، {nrul} حكم — {time.time()-t0:.0f}s", flush=True)
    db.execute("CREATE INDEX i1 ON mk_links(cluster_id)")
    db.execute("CREATE INDEX i2 ON mk_rulings(cluster_id)")
    db.commit()
    print("تم.")

if __name__ == "__main__":
    main()

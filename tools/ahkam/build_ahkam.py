# -*- coding: utf-8 -*-
"""
يجمع مخرجات المراحل في حزمة واحدة muhaddith_ahkam.db تُثبَّت في التطبيق إلى جانب حزمة المتون:
  - أحكام العلماء على الأحاديث (من المتن نفسه، ومن الطبعات المحقَّقة، ومن بلوغ المرام، ومن كتب التخريج والعلل)
  - كتب مختلف الحديث والناسخ والمنسوخ وأصول الجمع بين الأحاديث كاملةً، مقسَّمة مقاطع مربوطة بالأحاديث، مع بحث FTS5

  python3 build_ahkam.py --work work --corpus muhaddith_corpus_full.db --out muhaddith_ahkam.db
"""
import argparse, json, os, re, sqlite3, sys, time, zlib
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from common import norm, strip_diac

# توحيد أسماء العلماء ووفياتهم (لترتيب العرض زمنيًّا)
MERGE = {
    "أبو أحمد بن عدي الجرجاني": "ابن عدي", "أبو أحمد الجرجاني": "ابن عدي", "عبد الرحمن بن عمر الجورقاني": "الجورقاني",
    "أبو زرعة العراقي": "ابن العراقي (أبو زرعة)", "ابن حجر العسقلاني": "ابن حجر",
    "أحمد بن محمد الظاهري الحنفي": "ابن البخاري الظاهري", "محمد بن أبي بكر بن أبي عيسى المديني": "أبو موسى المديني",
    "الملا علي القاري": "ملا علي القاري", "علي بن المديني": "علي بن المديني", "أبو عبد الله الذهبي": "الذهبي",
    "المؤلف": None, "أبو محمد": None, "مؤلف الكتاب": None, "الشيخ": None, "شيخنا": None, "المصنف": None, "أبو يقول": None, "أبو هذا": None,
    "محمد بن موسى الحازمي": "الحازمي", "أبو حاتم بن حبان": "ابن حبان", "ابن ماجة": "ابن ماجه", "البييهقي": "البيهقي", "العقيلى": "العقيلي",
    "ابن ميعن": "يحيى بن معين", "ابن حنبل": "أحمد بن حنبل", "الضياء": "الضياء المقدسي", "أبو الحسن بن القطان": "ابن القطان الفاسي",
    "الحافظ أبو الفضل": "ابن حجر", "أبو الفضل بن حجر": "ابن حجر", "ابن حجر العسقلاني": "ابن حجر", "الحاكم أبو عبد الله": "الحاكم",
    "الرازيان": "أبو حاتم وأبو زرعة الرازيان", "أبو عبد الله الحاكم": "الحاكم", "أبو بكر البيهقي": "البيهقي", "أبو الحسن الدارقطني": "الدارقطني",
    "محمد بن إسماعيل": "البخاري", "أبو عيسى": "الترمذي", "أبو عيسى الترمذي": "الترمذي", "أبو داود السجستاني": "أبو داود",
}
DEATH = {
    "الشافعي": 204, "أحمد بن حنبل": 241, "البخاري": 256, "مسلم": 261, "أبو داود": 275, "الترمذي": 279, "النسائي": 303,
    "أبو حاتم الرازي": 277, "أبو زرعة الرازي": 264, "يحيى بن معين": 233, "علي بن المديني": 234, "ابن أبي حاتم": 327,
    "ابن خزيمة": 311, "ابن حبان": 354, "الطحاوي": 321, "ابن عدي": 365, "العقيلي": 322, "الدارقطني": 385, "الحاكم": 405,
    "أبو نعيم الأصبهاني": 430, "البيهقي": 458, "ابن عبد البر": 463, "الخطيب البغدادي": 463, "ابن حزم": 456, "البغوي": 516,
    "ابن الجوزي": 597, "ابن القطان الفاسي": 628, "ابن الصلاح": 643, "المنذري": 656, "النووي": 676, "ابن دقيق العيد": 702,
    "ابن تيمية": 728, "المزي": 742, "ابن عبد الهادي": 744, "الذهبي": 748, "ابن القيم": 751, "الزيلعي": 762, "ابن كثير": 774,
    "العراقي": 806, "الهيثمي": 807, "ابن الملقن": 804, "البوصيري": 840, "ابن حجر": 852, "السخاوي": 902, "السيوطي": 911,
    "ابن عراق الكناني": 963, "ملا علي القاري": 1014, "الشوكاني": 1250, "الألباني": 1420, "أحمد محمد شاكر": 1377,
    "شعيب الأرنؤوط": 1438, "عبد الفتاح أبو غدة": 1417, "محمد فؤاد عبد الباقي": 1388, "محمد محيي الدين عبد الحميد": 1392,
    "الطبراني": 360, "البزار": 292, "ابن شاهين": 385, "الحازمي": 584, "الأثرم": 273, "ابن قتيبة": 276, "ابن فورك": 406,
    "ابن أبي شيبة": 235, "إسحاق بن راهويه": 238, "أبو داود الطيالسي": 204, "الحميدي": 219, "ابن عساكر": 571,
    "الضياء المقدسي": 643, "الجورقاني": 543, "ابن العراقي (أبو زرعة)": 826, "أبو موسى المديني": 581, "ابن ماجه": 273,
    "مالك": 179, "الدارمي": 255, "أبو يعلى": 307, "ابن المنذر": 318, "الخطابي": 388, "ابن الجارود": 307, "الفسوي": 277,
    "أبو عوانة": 316, "ابن رجب": 795, "ابن عمار الشهيد": 317, "الطوسي": 312,
}
KIND = {"api": "modern", "bulugh": "classical", "text": "classical", "passage": "classical", "albani": "modern"}
# ثقة الربط الافتراضية بحسب طريقة الربط (١ = الرواية نفسها بعينها)
DEFAULT_CONF = {"api": 0.95, "bulugh": 0.75, "text": 1.0, "passage": 0.7, "albani": 0.7}
ALBANI_SEC_OFFSET = 100000
ALBANI_BOOK_OFFSET = 100

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--corpus", required=True, help="قاعدة الهاتف الكاملة لتحويل bhid/cluster إلى معرّفاتها")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    t0 = time.time()
    corpus = sqlite3.connect(a.corpus)
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript("""
      PRAGMA page_size=4096;
      CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
      CREATE TABLE scholars(id INTEGER PRIMARY KEY, name TEXT UNIQUE, death INTEGER, kind TEXT, rulings INTEGER);
      CREATE TABLE rulings(id INTEGER PRIMARY KEY, bhid TEXT, cluster_id INTEGER, scholar_id INTEGER, grade TEXT, level INTEGER,
                           source TEXT, ref TEXT, quote TEXT, origin TEXT, section_id INTEGER, via_bhid TEXT, conf REAL);
      CREATE TABLE mk_books(id INTEGER PRIMARY KEY, title TEXT, author TEXT, death INTEGER, kind TEXT, ord INTEGER,
                            edition TEXT, editor TEXT, publisher TEXT, vols INTEGER, sections INTEGER, words INTEGER);
      CREATE TABLE mk_sections(id INTEGER PRIMARY KEY, book_id INTEGER, ord INTEGER, title TEXT, vol INTEGER,
                               page_start INTEGER, page_end INTEGER, words INTEGER, text BLOB);
      CREATE TABLE mk_links(section_id INTEGER, cluster_id INTEGER, bhid TEXT, hits INTEGER, para INTEGER, conf REAL);
      CREATE VIRTUAL TABLE mk_fts USING fts5(title, body, content='', tokenize='unicode61 remove_diacritics 2');
    """)
    # ---- العلماء ----
    scholars = {}
    def scholar_id(name, kind):
        n = MERGE.get(name, name)
        if n is None:
            return None
        n = n.strip()
        if n not in scholars:
            scholars[n] = (len(scholars) + 1, DEATH.get(n), kind)
        return scholars[n][0]

    # ---- تحويل bhid → cluster (من قاعدة الهاتف) ----
    print("تحميل خريطة الأحاديث…", flush=True)
    bhid_cluster = {}
    for bhid, cl in corpus.execute("select bhid, cluster_id from hadiths"):
        bhid_cluster[bhid] = cl
    def cl_int(x):
        try:
            return int(x) if x not in (None, "") else None
        except ValueError:
            return None

    seen = set()
    n = 0
    def add(rec, origin, section_id=None):
        nonlocal n
        sid = scholar_id(rec["scholar"], KIND.get(origin, "classical"))
        if sid is None:
            # حكم بلا اسم صريح: ننسبه لمؤلف الكتاب إن عُرف من المصدر وإلا نتركه
            return
        bhid = rec.get("bhid")
        cl = cl_int(rec.get("cluster")) or (bhid_cluster.get(bhid) if bhid else None)
        if bhid is None and cl is None:
            return
        grade = rec["grade"].strip()
        key = (bhid, cl, sid, norm(grade), rec.get("source"), rec.get("ref"))
        if key in seen:
            return
        seen.add(key)
        conf = rec.get("conf")
        if conf is None:
            conf = 1.0 if (origin == "text" and bhid and rec.get("own_cluster", True)) else DEFAULT_CONF.get(origin, 0.7)
        db.execute("INSERT INTO rulings(bhid, cluster_id, scholar_id, grade, level, source, ref, quote, origin, section_id, via_bhid, conf) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                   (bhid, cl, sid, grade, rec.get("level"), rec.get("source"), rec.get("ref"), rec.get("quote"), origin, section_id, rec.get("via"), conf))
        n += 1

    for fn, origin in [("api_rulings.jsonl", "api"), ("text_rulings.jsonl", "text"), ("bulugh_rulings.jsonl", "bulugh"), ("albani_rulings.jsonl", "albani")]:
        p = os.path.join(a.work, fn)
        if not os.path.exists(p):
            print("!! لا يوجد", p); continue
        for line in open(p, encoding="utf-8"):
            rec = json.loads(line)
            if origin == "text" and rec.get("matched"):
                rec["conf"] = rec.get("conf", 0.7)
            sec = rec.get("section")
            add(rec, origin, (sec + ALBANI_SEC_OFFSET) if (origin == "albani" and sec) else None)
        print(f"  {fn}: مجموع الأحكام حتى الآن {n}", flush=True)

    # ---- الكتب والمقاطع ----
    tx = sqlite3.connect(os.path.join(a.work, "texts.sqlite"))
    for row in tx.execute("select id, title, author, death, kind, ord, edition, editor, publisher, vols, sections, words from mk_books"):
        db.execute("INSERT INTO mk_books VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
    ns = 0
    for sid, book_id, ordv, title, vol, ps, pe, words, blob in tx.execute("select id, book_id, ord, title, vol, page_start, page_end, words, text from mk_sections"):
        db.execute("INSERT INTO mk_sections VALUES(?,?,?,?,?,?,?,?,?)", (sid, book_id, ordv, title, vol, ps, pe, words, blob))
        text = zlib.decompress(blob).decode("utf-8")
        db.execute("INSERT INTO mk_fts(rowid, title, body) VALUES(?,?,?)", (sid, strip_diac(title or ""), strip_diac(text)))
        ns += 1
    nl = 0
    for sid, cl, bhid, hits, para in tx.execute("select section_id, cluster_id, bhid, hits, para from mk_links"):
        c = cl_int(cl)
        if c is None:
            continue
        conf = round(min(1.0, 0.4 + hits / 15.0), 2)
        db.execute("INSERT INTO mk_links VALUES(?,?,?,?,?,?)", (sid, c, bhid, hits, para, conf))
        nl += 1
    book_title = dict(tx.execute("select id, title || ' — ' || author from mk_books").fetchall())
    # ---- كتب الألباني (مداخل مرقّمة) ----
    ap_path = os.path.join(a.work, "albani.sqlite")
    if os.path.exists(ap_path):
        ab = sqlite3.connect(ap_path)
        for row in ab.execute("select id, title, author, death, kind, ord, edition, editor, publisher, vols, sections, words from mk_books"):
            row = list(row); row[0] += ALBANI_BOOK_OFFSET
            db.execute("INSERT INTO mk_books VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
            book_title[row[0]] = f"{row[1]} — {row[2]}"
        for sid, book_id, ordv, title, vol, ps, pe, words, blob in ab.execute("select id, book_id, ord, title, vol, page_start, page_end, words, text from mk_sections"):
            sid2 = sid + ALBANI_SEC_OFFSET
            db.execute("INSERT INTO mk_sections VALUES(?,?,?,?,?,?,?,?,?)", (sid2, book_id + ALBANI_BOOK_OFFSET, ordv, title, vol, ps, pe, words, blob))
            text = zlib.decompress(blob).decode("utf-8")
            db.execute("INSERT INTO mk_fts(rowid, title, body) VALUES(?,?,?)", (sid2, strip_diac(title or ""), strip_diac(text)))
            ns += 1
        for sid, cl, bhid, hits, para, conf in ab.execute("select section_id, cluster_id, bhid, hits, para, conf from mk_links"):
            c = cl_int(cl)
            if c is None:
                continue
            db.execute("INSERT INTO mk_links VALUES(?,?,?,?,?,?)", (sid + ALBANI_SEC_OFFSET, c, bhid, hits, para, conf))
            nl += 1
    sec_ref = {}
    for sid, vol, ps, title in tx.execute("select id, vol, page_start, title from mk_sections"):
        r = []
        if vol: r.append(f"ج{vol}")
        if ps: r.append(f"ص{ps}")
        sec_ref[sid] = " ".join(r) + ((" — " + title[:60]) if title else "")
    link_conf = {}
    for sid, cl, conf in db.execute("select section_id, cluster_id, conf from mk_links"):
        link_conf[(sid, cl)] = max(conf or 0, link_conf.get((sid, cl), 0))
    for sid, book_id, cl, scholar, grade, level, quote, para in tx.execute("select section_id, book_id, cluster_id, scholar, grade, level, quote, para from mk_rulings"):
        add({"bhid": None, "cluster": cl, "scholar": scholar, "grade": grade, "level": level, "conf": link_conf.get((sid, cl_int(cl)), 0.6),
             "source": book_title.get(book_id), "ref": sec_ref.get(sid, ""), "quote": quote}, "passage", sid)
    print(f"  مقاطع {ns}، روابط {nl}، أحكام {n}", flush=True)

    for name, (sid, death, kind) in scholars.items():
        cnt = db.execute("select count(*) from rulings where scholar_id=?", (sid,)).fetchone()[0]
        db.execute("INSERT INTO scholars VALUES(?,?,?,?,?)", (sid, name, death, kind, cnt))
    db.executescript("""
      CREATE INDEX r_bhid ON rulings(bhid);
      CREATE INDEX r_cluster ON rulings(cluster_id);
      CREATE INDEX r_scholar ON rulings(scholar_id);
      CREATE INDEX l_cluster ON mk_links(cluster_id);
      CREATE INDEX l_section ON mk_links(section_id);
      CREATE INDEX s_book ON mk_sections(book_id, ord);
    """)
    meta = {
        "schema_version": "2", "dataset": "ahkam", "built": time.strftime("%Y-%m-%d"),
        "rulings_count": str(n), "scholars_count": str(len(scholars)), "sections_count": str(ns), "links_count": str(nl),
        "books_count": str(len(book_title)),
        "clusters_with_rulings": str(db.execute("select count(distinct cluster_id) from rulings").fetchone()[0]),
        "hadiths_with_rulings": str(db.execute("select count(distinct bhid) from rulings where bhid is not null").fetchone()[0]),
        "albani_rulings": str(db.execute("select count(*) from rulings r join scholars s on s.id=r.scholar_id where s.name='الألباني'").fetchone()[0]),
    }
    for k, v in meta.items():
        db.execute("INSERT INTO meta VALUES(?,?)", (k, v))
    db.commit()
    db.execute("INSERT INTO mk_fts(mk_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"الحجم: {os.path.getsize(a.out)/1e6:.1f} م.ب — {time.time()-t0:.0f}s")

if __name__ == "__main__":
    main()

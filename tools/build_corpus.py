# -*- coding: utf-8 -*-
"""
يبني قاعدة الأندرويد المدمجة من قاعدة «المحدِّث» الأصلية (clusters.db + words.db).

  python3 build_corpus.py --src /path/clusters.db --words /path/words.db --out muhaddith_corpus.db [--max-priority 8]

المخطط الناتج:
  books, rawis, clusters, hadiths (النصوص مضغوطة zlib), hadith_rawis, hadith_sahaba,
  cluster_sahaba, morph (كلمة مطبَّعة → مفتاح صرفي), hadith_fts (FTS5 بلا محتوى), meta
"""
import argparse, os, re, sqlite3, sys, time, zlib, unicodedata

DIAC = re.compile("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
PUNCT = re.compile("[^\u0621-\u064A0-9a-zA-Z\\s]")
SPACES = re.compile(r"\s+")
MARKUP = re.compile(r"/\d+|L\d+|\*|\r|\n")     # علامات raw_sanad: /94 L4698 *
LMARK = re.compile(r"L(\d+)")
QUOTES = re.compile(r'^[\s"«»]+|[\s"«»]+$')
QREF = re.compile(r"@[\s\d:]*@")                 # مرجع داخلي مثل @ 1 : 208 @
LEADNUM = re.compile(r"^\s*\d+\s+")
HEAD = re.compile("^(باب|بَاب|كتاب|كِتَاب|أبواب|أَبْوَاب)")

def _plain(s):
    return DIAC.sub("", s)

def split_matn(s):
    """يفصل عنوان الباب الملحق بآخر المتن (بعد # أو بعد آخر علامة اقتباس) ويعيد (المتن، الباب)"""
    if not s:
        return "", None
    s = QREF.sub(" ", s)
    chapter = None
    if "#" in s:
        head, _, tail = s.partition("#")
        tail = tail.strip()
        if tail and HEAD.match(_plain(tail)):
            chapter = clean_display(tail)
        s = head
    st = s.strip()
    i = st.rfind('"')
    if i > 0 and i < len(st) - 1:
        tail = st[i + 1:].strip()
        if tail and HEAD.match(_plain(tail)):
            if not chapter:
                chapter = clean_display(tail)
            s = st[: i + 1]
    return clean_display(s), chapter

TR = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي", "ء": ""})

def norm(s):
    if not s:
        return ""
    s = unicodedata.normalize("NFC", s)
    s = DIAC.sub("", s).translate(TR)
    s = PUNCT.sub(" ", s)
    return SPACES.sub(" ", s).strip()

def clean_display(s):
    """نص للعرض: يزيل علامات الترميز الداخلية ويبقي التشكيل"""
    if not s:
        return ""
    s = MARKUP.sub(" ", s)
    s = QREF.sub(" ", s).replace("#", " ").replace("@", " ")
    s = SPACES.sub(" ", s).strip()
    s = QUOTES.sub("", s)
    return s

HOKM = {0: "صحيح", 1: "حسن", 2: "ضعيف", 3: "شديد الضعف", 4: "متهم", 5: "موضوع"}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--words", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-priority", type=int, default=None,
                    help="اقتصر على الكتب التي أولويتها ≤ هذا الرقم (٨ = الكتب التسعة)")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--dataset", default="full")
    ap.add_argument("--empty", action="store_true", help="مخطط فارغ فقط (للحزمة المشحونة الصغيرة بلا بيانات)")
    a = ap.parse_args()

    if os.path.exists(a.out):
        os.remove(a.out)
    src = sqlite3.connect(f"file:{a.src}?mode=ro", uri=True)
    wdb = sqlite3.connect(f"file:{a.words}?mode=ro", uri=True)
    out = sqlite3.connect(a.out)
    out.executescript("""
    PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA cache_size=-400000; PRAGMA temp_store=MEMORY;
    CREATE TABLE books(id INTEGER PRIMARY KEY, src_id TEXT, title TEXT, title_norm TEXT, author TEXT,
                       death_year TEXT, category_id TEXT, priority INTEGER, hadith_count INTEGER);
    CREATE TABLE rawis(id INTEGER PRIMARY KEY, name TEXT, name_norm TEXT, shohra TEXT, konya TEXT, lakab TEXT,
                       rotba INTEGER, wasf_rotba TEXT, tabaka INTEGER, death_year TEXT, balad_wafa TEXT,
                       bukhari INTEGER, muslim INTEGER, marweyaat INTEGER);
    CREATE TABLE clusters(id INTEGER PRIMARY KEY, taraf TEXT, hokm_type INTEGER, sahaba_count INTEGER, mokararat_count INTEGER);
    CREATE TABLE hadiths(id INTEGER PRIMARY KEY, bhid TEXT, book_id INTEGER, hadith_num INTEGER, page_num INTEGER,
                         cluster_id INTEGER, hokm INTEGER, block_type INTEGER, chapter TEXT, sanad BLOB, matn BLOB);
    CREATE TABLE hadith_rawis(hadith_id INTEGER, rawi_id INTEGER, pos INTEGER);
    CREATE TABLE hadith_sahaba(hadith_id INTEGER, rawi_id INTEGER);
    CREATE TABLE cluster_sahaba(cluster_id INTEGER, rawi_id INTEGER, way_count INTEGER, taraf TEXT);
    CREATE TABLE morph(word_norm TEXT, key TEXT);
    CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    CREATE VIRTUAL TABLE hadith_fts USING fts5(matn, sanad, stems, content='', tokenize='unicode61');
    """)

    # ---------- المعجم الصرفي ----------
    t0 = time.time()
    morph = {}
    rows = [] if a.empty else wdb.execute("SELECT word, root, wazn FROM words_morphology")
    for word, root, wazn in rows:
        key = (wazn if root == "غير مشتق" else root) or ""
        key = norm(key)
        w = norm(word)
        if not w or not key:
            continue
        morph.setdefault(w, set()).add(key)
    out.executemany("INSERT INTO morph VALUES(?,?)",
                    ((w, k) for w, ks in morph.items() for k in ks))
    print(f"morph: {len(morph)} words in {time.time()-t0:.0f}s", flush=True)

    def stems_of(norm_text):
        outl = []
        for tok in norm_text.split():
            ks = morph.get(tok)
            if ks:
                outl.extend(ks)
            else:
                outl.append(tok)
        return " ".join(outl)

    # ---------- الكتب ----------
    where = ""
    if a.max_priority is not None:
        where = f"WHERE CAST(priority AS INTEGER) <= {a.max_priority}"
    books = [] if a.empty else src.execute(f"SELECT book_id, name, author, death_date, category_id, priority FROM books {where}").fetchall()
    book_ids = set(b[0] for b in books)
    for (bid, name, author, dd, cat, pr) in books:
        out.execute("INSERT INTO books VALUES(?,?,?,?,?,?,?,?,0)",
                    (int(bid), bid, name, norm(name), author, dd, cat, int(pr) if pr not in (None, "") else 9999))
    print(f"books: {len(books)}", flush=True)

    # ---------- الرواة ----------
    n = 0
    rawi_rows = [] if a.empty else src.execute("""SELECT original_id, name, shohra, konya, lakab, rotba, wasf_rotba, tabaka,
                                   death_year, balad_wafa, bokhary, moslem, marweyaat FROM rawis""")
    for r in rawi_rows:
        try:
            rid = int(r[0])
        except (TypeError, ValueError):
            continue
        out.execute("INSERT OR IGNORE INTO rawis VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (rid, r[1], norm(r[1]) + " " + norm(r[2]), r[2], r[3], r[4], r[5], r[6], r[7],
                     r[8], r[9], r[10], r[11], r[12]))
        n += 1
    print(f"rawis: {n}", flush=True)

    # ---------- الأحاديث المجمّعة ----------
    for c in ([] if a.empty else src.execute("SELECT cluster_id, taraf, hokm_type, sa7aba_count, mokararat_count FROM clusters")):
        out.execute("INSERT INTO clusters VALUES(?,?,?,?,?)", (int(c[0]), c[1], c[2], c[3], c[4]))
    for s in ([] if a.empty else src.execute("SELECT cluster_id, sa7aby_id, way_count, taraf_text FROM sahabi_cluster_details")):
        try:
            out.execute("INSERT INTO cluster_sahaba VALUES(?,?,?,?)", (int(s[0]), int(s[1]), s[2], s[3]))
        except (TypeError, ValueError):
            pass
    print("clusters done", flush=True)

    # ---------- الأحاديث ----------
    t0 = time.time()
    q = """SELECT h.bhid, h.book_id, h.hadith_id, h.cluster_id, h.sa7aby_id, h.hokm,
                  t.block_type, t.hadith_num, t.page_num, t.raw_sanad, t.clean_matn, t.text
           FROM hadiths h JOIN hadith_texts t ON t.bhid = h.bhid"""
    if book_ids and a.max_priority is not None:
        q += " WHERE h.book_id IN (%s)" % ",".join("'%s'" % b for b in book_ids)
    if a.limit:
        q += f" LIMIT {a.limit}"
    if a.empty:
        q += " LIMIT 0"
    hid = 0
    batch_h, batch_f, batch_r, batch_s = [], [], [], []
    counts = {}
    def flush():
        out.executemany("INSERT INTO hadiths VALUES(?,?,?,?,?,?,?,?,?,?,?)", batch_h)
        out.executemany("INSERT INTO hadith_fts(rowid, matn, sanad, stems) VALUES(?,?,?,?)", batch_f)
        out.executemany("INSERT INTO hadith_rawis VALUES(?,?,?)", batch_r)
        out.executemany("INSERT INTO hadith_sahaba VALUES(?,?)", batch_s)
        batch_h.clear(); batch_f.clear(); batch_r.clear(); batch_s.clear()

    for (bhid, book_id, hadith_id, cluster_id, sa7aby, hokm, btype, hnum, pnum, raw_sanad, clean_matn, text) in src.execute(q):
        hid += 1
        matn_disp, chapter = split_matn(clean_matn)
        sanad_disp = clean_display(LEADNUM.sub("", raw_sanad or ""))
        if not matn_disp and text:
            matn_disp, chapter = split_matn(text)
        matn_n = norm(matn_disp)
        sanad_n = norm(sanad_disp)
        try:
            cid = int(cluster_id) if cluster_id not in (None, "") else None
        except ValueError:
            cid = None
        batch_h.append((hid, bhid, int(book_id), hnum if hnum is not None else hadith_id, pnum, cid, hokm, btype, chapter,
                        zlib.compress(sanad_disp.encode("utf-8"), 9) if sanad_disp else None,
                        zlib.compress(matn_disp.encode("utf-8"), 9)))
        batch_f.append((hid, matn_n, sanad_n, stems_of(matn_n)))
        seen = set()
        for pos, m in enumerate(LMARK.findall(raw_sanad or "")):
            rid = int(m)
            if rid in seen:
                continue
            seen.add(rid)
            batch_r.append((hid, rid, pos))
        for sid in (sa7aby or "").split(","):
            sid = sid.strip()
            if sid.isdigit():
                batch_s.append((hid, int(sid)))
        counts[int(book_id)] = counts.get(int(book_id), 0) + 1
        if len(batch_h) >= 2000:
            flush()
            if hid % 20000 == 0:
                print(f"  {hid} hadiths… {time.time()-t0:.0f}s", flush=True)
    flush()
    for bid, c in counts.items():
        out.execute("UPDATE books SET hadith_count=? WHERE id=?", (c, bid))
    out.execute("DELETE FROM books WHERE hadith_count=0")
    print(f"hadiths: {hid} in {time.time()-t0:.0f}s", flush=True)

    # ---------- الفهارس ----------
    t0 = time.time()
    out.executescript("""
    CREATE INDEX idx_h_book ON hadiths(book_id);
    CREATE INDEX idx_h_cluster ON hadiths(cluster_id);
    CREATE INDEX idx_h_bhid ON hadiths(bhid);
    CREATE INDEX idx_hr_rawi ON hadith_rawis(rawi_id);
    CREATE INDEX idx_hr_hadith ON hadith_rawis(hadith_id);
    CREATE INDEX idx_hs_hadith ON hadith_sahaba(hadith_id);
    CREATE INDEX idx_hs_rawi ON hadith_sahaba(rawi_id);
    CREATE INDEX idx_cs_cluster ON cluster_sahaba(cluster_id);
    CREATE INDEX idx_morph_word ON morph(word_norm);
    CREATE INDEX idx_rawis_name ON rawis(name_norm);
    INSERT INTO hadith_fts(hadith_fts) VALUES('optimize');
    """)
    for k, v in [("schema_version", "2"), ("dataset", a.dataset),
                 ("hadiths_count", str(hid)), ("books_count", str(len(counts))),
                 ("rawis_count", str(n)), ("hokm_labels", ";".join(f"{k}={v}" for k, v in HOKM.items())),
                 ("source", "muhaddith.murabbie.org clusters.db + words.db")]:
        out.execute("INSERT INTO meta VALUES(?,?)", (k, v))
    out.commit()
    out.execute("VACUUM")
    out.commit()
    out.close()
    print(f"indexes+vacuum: {time.time()-t0:.0f}s ; size = {os.path.getsize(a.out)/1e6:.1f} MB", flush=True)

if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""
كتب الألباني (نصوص OCR من مكتبة الشاملة الوقفية على HuggingFace: ieasybooks-org/shamela-waqfeya-library):
السلسلتان، الإرواء، صحيح/ضعيف الجامع، صحيح/ضعيف الترغيب، صحيح/ضعيف الأدب المفرد، صحيح موارد الظمآن، غاية المرام.
يقسم كل كتاب مداخل مرقّمة، ويربط كل مدخل بالحديث المجمَّع في المتن بمطابقة نصية مع درجة ثقة، ويستخرج حكم الألباني
(من العلامة الصريحة بعد المتن، أو من كون الكتاب «صحيح…/ضعيف…»)، وما نقله عن غيره.

  python3 albani.py --dir albani/ --work work --out work/albani.sqlite --rulings work/albani_rulings.jsonl
"""
import argparse, glob, json, os, re, sqlite3, sys, time, zlib
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from matcher import CorpusIndex, N
from common import content_words, strip_diac, grade_level, norm
from rulings import find_rulings

AR_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")

# (بادئة اسم الملف، العنوان، النوع، الحكم الضمني، المستوى، الطبعة)
BOOKS = [
    ("سلسلة_الأحاديث_الصحيحة", "سلسلة الأحاديث الصحيحة وشيء من فقهها وفوائدها", "albani", "صحيح (أورده في السلسلة الصحيحة)", 0, "مكتبة المعارف، الرياض"),
    ("سلسلة_الأحاديث_الضعيفة", "سلسلة الأحاديث الضعيفة والموضوعة وأثرها السيئ في الأمة", "albani", "ضعيف (أورده في السلسلة الضعيفة)", 2, "مكتبة المعارف، الرياض"),
    ("إرواء_الغليل", "إرواء الغليل في تخريج أحاديث منار السبيل", "albani", None, None, "المكتب الإسلامي، ط١"),
    ("صحيح_الجامع_الصغير", "صحيح الجامع الصغير وزيادته", "albani", "صحيح (أورده في صحيح الجامع)", 0, "المكتب الإسلامي، ط٣"),
    ("ضعيف_الجامع_الصغير", "ضعيف الجامع الصغير وزيادته", "albani", "ضعيف (أورده في ضعيف الجامع)", 2, "المكتب الإسلامي"),
    ("صحيح_الترغيب", "صحيح الترغيب والترهيب", "albani", "صحيح أو حسن (أورده في صحيح الترغيب)", 0, "مكتبة المعارف"),
    ("ضعيف_الترغيب", "ضعيف الترغيب والترهيب", "albani", "ضعيف (أورده في ضعيف الترغيب)", 2, "مكتبة المعارف"),
    ("صحيح_الأدب_المفرد", "صحيح الأدب المفرد", "albani", "صحيح (أورده في صحيح الأدب المفرد)", 0, "دار الدليل، ط٤"),
    ("ضعيف_الأدب_المفرد", "ضعيف الأدب المفرد", "albani", "ضعيف (أورده في ضعيف الأدب المفرد)", 2, "دار الدليل، ط٤"),
    ("صحيح_موارد_الظمآن", "صحيح موارد الظمآن إلى زوائد ابن حبان", "albani", "صحيح (أورده في صحيح موارد الظمآن)", 0, "دار الصميعي"),
    ("غاية_المرام", "غاية المرام في تخريج أحاديث الحلال والحرام", "albani", None, None, "المكتب الإسلامي، ط١"),
]

ENTRY_RE = re.compile(r"^\s*[\(\[]?\s*([٠-٩۰-۹0-9]{1,5})\s*[\)\]]?\s*[-ـ–—]\s*(.*)$")
PAGE_LINE = re.compile(r"^\s*[-ـ]?\s*(?:ص\s*)?([٠-٩۰-۹0-9]{1,4})\s*[-ـ]?\s*\.?\s*$")
# العلامة الصريحة بعد المتن: «صحيح .» «ضعيف .» «(صحيح)» «ص لغيره» «ح لغيره» «موضوع .»
LABEL_RE = re.compile(r"(?<![ء-ي])\(?\s*(صحيح لغيره|حسن لغيره|حسن صحيح|صحيح الإسناد|حسن الإسناد|ضعيف جدا|ضعيف جداً|ضعيف الإسناد|صحيح بشواهده|حسن بشواهده|موضوع|باطل|منكر جدا|منكر|شاذ|لا أصل له|ضعيف|صحيح|حسن|ص لغيره|ح لغيره|ص|ح|ض)\s*\)?\s*(?:[.،:\n]|$)")
ABBR = {"ص": "صحيح", "ح": "حسن", "ض": "ضعيف", "ص لغيره": "صحيح لغيره", "ح لغيره": "حسن لغيره"}


def to_int(s):
    try:
        return int(s.translate(AR_DIGITS))
    except ValueError:
        return None


def parse_file(path):
    """يعيد قائمة صفحات: (page_no أو None، النص)"""
    raw = open(path, encoding="utf-8", errors="ignore").read()
    pages = raw.split("PAGE_SEPARATOR")
    out = []
    for p in pages:
        lines = [l.rstrip() for l in p.split("\n")]
        pno = None
        # رقم الصفحة سطر مستقل في أول الصفحة أو آخرها
        cand = [l for l in lines[:3] + lines[-4:] if PAGE_LINE.match(l or "")]
        for c in cand:
            v = to_int(PAGE_LINE.match(c).group(1))
            if v and 0 < v < 3000:
                pno = v
        body = [l for l in lines if not PAGE_LINE.match(l or "")]
        out.append((pno, "\n".join(body)))
    return out


def vol_of(fname):
    m = re.search(r"(\d{1,2})(?:p)?\.txt$", fname)
    return int(m.group(1)) if m else None


def split_entries(book_lines):
    """book_lines: قائمة (vol, page, line). يختار المداخل المرقّمة بأطول سلسلة متزايدة (لتجاوز أخطاء الـOCR)،
    ويعيد قائمة dict(num, vol, page, text)."""
    cands = []  # (index, num, rest)
    for i, (vol, page, line) in enumerate(book_lines):
        m = ENTRY_RE.match(line)
        if not m:
            continue
        n = to_int(m.group(1))
        if n is None or n <= 0 or n > 20000 or len(m.group(2).strip()) < 3:
            continue
        cands.append((i, n, m.group(2)))
    # أطول سلسلة متزايدة تمامًا (LIS) على الأرقام بترتيب ورودها
    import bisect
    tails, tails_idx, prev = [], [], [-1] * len(cands)
    for k, (_, n, _) in enumerate(cands):
        j = bisect.bisect_left(tails, n)
        if j == len(tails):
            tails.append(n); tails_idx.append(k)
        else:
            tails[j] = n; tails_idx[j] = k
        prev[k] = tails_idx[j - 1] if j > 0 else -1
    chosen = []
    k = tails_idx[-1] if tails_idx else -1
    while k >= 0:
        chosen.append(k); k = prev[k]
    chosen.reverse()
    entries = []
    for ci, k in enumerate(chosen):
        i, n, rest = cands[k]
        end = cands[chosen[ci + 1]][0] if ci + 1 < len(chosen) else len(book_lines)
        vol, page, _ = book_lines[i]
        lines = [rest] + [l for _, _, l in book_lines[i + 1:end]]
        text = re.sub(r"\n{2,}", "\n", "\n".join(l for l in lines if l.strip())).strip()
        entries.append({"num": n, "vol": vol, "page": page, "text": text})
    return entries


def explicit_label(text):
    """العلامة الصريحة للحكم في أول ٤٠٠ حرف بعد المتن (بعد أول علامة إغلاق اقتباس أو قوس)"""
    head = text[:900]
    # نتجاوز المتن نفسه: بعد آخر » أو ) في أول ٥٠٠ حرف
    cut = max(head.rfind("»", 0, 500), head.rfind(")", 0, 500), head.rfind("\"", 0, 500))
    zone = head[cut + 1: cut + 400] if cut > 0 else head[:400]
    m = LABEL_RE.search(zone)
    if not m:
        return None
    g = m.group(1).strip()
    g = ABBR.get(g, g)
    # «ص» و«ح» و«ض» المجردة تُقبل فقط إن كانت على سطر شبه مستقل
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--rulings", required=True)
    a = ap.parse_args()
    ix = CorpusIndex(a.work)
    cluster_nw = {}
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript("""
      CREATE TABLE mk_books(id INTEGER PRIMARY KEY, uri TEXT, title TEXT, author TEXT, death INTEGER, kind TEXT, ord INTEGER,
                            edition TEXT, editor TEXT, publisher TEXT, vols INTEGER, sections INTEGER, words INTEGER);
      CREATE TABLE mk_sections(id INTEGER PRIMARY KEY, book_id INTEGER, ord INTEGER, title TEXT, vol INTEGER,
                               page_start INTEGER, page_end INTEGER, words INTEGER, text BLOB);
      CREATE TABLE mk_links(section_id INTEGER, cluster_id TEXT, bhid TEXT, hits INTEGER, para INTEGER, conf REAL);
    """)
    out = open(a.rulings, "w", encoding="utf-8")
    sec_id = 0
    stats = []
    t0 = time.time()
    for bi, (prefix, title, kind, implicit, ilevel, edition) in enumerate(BOOKS, 1):
        files = sorted(glob.glob(os.path.join(a.dir, prefix + "*")))
        files = [f for f in files if os.path.getsize(f) > 5000 and not re.search(r"\d+p\.txt$", f)]
        if not files:
            print("!! لا ملفات لـ", title); continue
        n_entries = n_linked = n_rul = 0
        words_total = 0
        ord_ = 0
        vols = set()
        book_lines = []
        for f in files:
            vol = vol_of(os.path.basename(f)) if len(files) > 1 else None
            if vol: vols.add(vol)
            page = None
            for pno, text in parse_file(f):
                if pno is not None:
                    page = pno
                elif page is not None:
                    page += 1
                for line in text.split("\n"):
                    book_lines.append((vol, page, line))
        entries = split_entries(book_lines)
        for e in entries:
            vol = e["vol"]
            text = e["text"]
            if len(text) < 25:
                continue
            sec_id += 1; ord_ += 1; n_entries += 1
            words_total += len(text.split())
            db.execute("INSERT INTO mk_sections VALUES(?,?,?,?,?,?,?,?,?)",
                       (sec_id, bi, ord_, f"حديث {e['num']}", vol, e["page"], e["page"], len(text.split()), zlib.compress(text.encode("utf-8"), 9)))
            # المطابقة: أول ٧٠ كلمة من المدخل (المتن غالبًا)
            plain = strip_diac(text)
            ws = content_words(plain)[:70]
            q = " ".join(ws)
            m = ix.match(q, min_hits=3)
            cluster = None; via = None; conf = 0.0; hits = 0
            if m:
                best = {}
                for idx, h, cov in m:
                    cl = ix.cluster[idx]
                    if not cl:
                        continue
                    if cl not in best or h > best[cl][1]:
                        best[cl] = (idx, h, cov)
                ranked = sorted(best.items(), key=lambda x: (-x[1][1], -x[1][2]))
                if ranked:
                    cl, (idx, h, cov) = ranked[0]
                    h2 = ranked[1][1][1] if len(ranked) > 1 else 0
                    margin = 1 - (h2 / h if h else 1)
                    conf = round(min(1.0, 0.5 * min(1.0, cov) + 0.3 * min(1.0, h / 8) + 0.2 * margin), 2)
                    if h >= 4 and cov >= 0.25:
                        cluster = cl; via = ix.bhid[idx]; hits = h
            if cluster:
                n_linked += 1
                db.execute("INSERT INTO mk_links VALUES(?,?,?,?,?,?)", (sec_id, cluster, via, hits, 0, conf))
                ref = " ".join(x for x in [f"ج{vol}" if vol else "", f"ص{e['page']}" if e["page"] else "", f"رقم {e['num']}"] if x)
                label = explicit_label(plain)
                if label:
                    grade = label; lvl = grade_level(label); how = "explicit"
                elif implicit:
                    grade = implicit; lvl = ilevel; how = "implicit"
                else:
                    grade = None
                if grade:
                    out.write(json.dumps({"bhid": None, "via": via, "cluster": cluster, "scholar": "الألباني", "grade": grade, "level": lvl,
                                          "source": title, "ref": ref, "quote": "…" + plain[:220].replace("\n", " ") + "…",
                                          "origin": "albani", "conf": conf, "section": sec_id, "how": how}, ensure_ascii=False) + "\n")
                    n_rul += 1
                seen = set()
                for phrase, speaker, s, en in find_rulings(plain, None):
                    sch = speaker or "الألباني"
                    key = (sch, norm(phrase))
                    if key in seen:
                        continue
                    seen.add(key)
                    if sch == "الألباني" and label and norm(phrase) == norm(label):
                        continue
                    out.write(json.dumps({"bhid": None, "via": via, "cluster": cluster, "scholar": sch, "grade": phrase, "level": grade_level(phrase),
                                          "source": title, "ref": ref, "quote": "…" + plain[max(0, s - 110):en + 40].replace("\n", " ").strip() + "…",
                                          "origin": "albani", "conf": conf, "section": sec_id, "how": "text"}, ensure_ascii=False) + "\n")
                    n_rul += 1
        db.execute("INSERT INTO mk_books VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (bi, prefix, title, "الألباني", 1420, kind, 60 + bi, edition, None, None, len(vols) or 1, n_entries, words_total))
        db.commit()
        stats.append((title, n_entries, n_linked, n_rul))
        print(f"{title}: {n_entries} مدخل، مربوط {n_linked}، أحكام {n_rul} — {time.time()-t0:.0f}s", flush=True)
    out.close()
    db.execute("CREATE INDEX i1 ON mk_links(cluster_id)")
    db.commit()
    print("تم.")


if __name__ == "__main__":
    main()

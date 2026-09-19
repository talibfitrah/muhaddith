# -*- coding: utf-8 -*-
"""يحاكي QueryPlanner + FtsExpression + الدمج على قاعدة مدمجة حقيقية ويطبع النتائج."""
import sqlite3, re, sys, zlib, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
from build_corpus import norm

db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
STOP = set("من في علي الي عن ان انه ما لا هو هي قال قالت حدثنا حدثني اخبرنا عند الذي التي وقال ثم او و ف ب ل كان كانت هذا هذه به له لم قد كل بن ابن رضي الله عنه عنها".split())
ARTICLES = ["وبال","وال","بال","كال","فال","ولل","ال","لل"]

def strip_article(w):
    for p in ARTICLES:
        if len(w)-len(p) >= 2 and w.startswith(p): return w[len(p):]
    if len(w) >= 4 and w[0] in "وف": return w[1:]
    return w

def roots_of(w):
    r = [x[0] for x in db.execute("SELECT DISTINCT key FROM morph WHERE word_norm=? LIMIT 6", (w,))]
    if r: return r
    s = strip_article(w)
    if s != w and len(s) >= 2:
        return [x[0] for x in db.execute("SELECT DISTINCT key FROM morph WHERE word_norm=? LIMIT 6", (s,))]
    return []

def plan(raw):
    t = raw.strip(); phrase = len(t) > 2 and t.startswith('"') and t.endswith('"'); t = t.strip('"')
    terms = []
    for chunk in t.split():
        neg = chunk.startswith("-"); w = chunk.lstrip("-")
        n = norm(w)
        if not n or len(n) <= 1 or (not neg and n in STOP): continue
        terms.append((n, roots_of(n), neg))
    return terms, phrase

def q(s): return '"' + s.replace('"', '""') + '"'
def group(alts): return q(alts[0]) if len(alts) == 1 else "(" + " OR ".join(q(a) for a in alts) + ")"

def expr(terms, phrase, engine, scope="MATN"):
    pos = [t for t in terms if not t[2]]; neg = [t for t in terms if t[2]]
    if not pos: return None
    cols = {"LITERAL": {"MATN": ["matn"], "ISNAD": ["sanad"], "BOTH": ["matn", "sanad"]},
            "MORPH": {"MATN": ["stems"], "ISNAD": ["sanad"], "BOTH": ["stems", "sanad"]}}[engine][scope]
    parts = []
    for col in cols:
        def alts(t): return (t[1] or [t[0]]) if (engine == "MORPH" and col == "stems") else [t[0]]
        if engine == "LITERAL" and phrase: body = q(" ".join(t[0] for t in pos))
        else: body = " AND ".join(group(alts(t)) for t in pos)
        if neg: body = f"({body}) NOT ({' OR '.join(group(alts(t)) for t in neg)})"
        parts.append(f"{col} : ({body})")
    return " OR ".join(parts)

W = {"LITERAL": 1.0, "MORPH": 0.72}
def run(raw, engines=("LITERAL", "MORPH"), scope="MATN", limit=400):
    terms, phrase = plan(raw)
    per = {}
    t0 = time.time()
    for e in engines:
        x = expr(terms, phrase, e, scope)
        if not x: continue
        ids = [r[0] for r in db.execute(
            "SELECT f.rowid FROM hadith_fts f JOIN hadiths h ON h.id=f.rowid WHERE hadith_fts MATCH ? "
            "ORDER BY bm25(hadith_fts,1.0,0.6,0.9), h.book_id, h.hadith_num LIMIT ?", (x, limit))]
        if ids: per[e] = ids
    dt = time.time() - t0
    fused, by = {}, {}
    for e, ids in per.items():
        for rank, i in enumerate(ids):
            fused[i] = fused.get(i, 0) + W[e] / (20 + rank + 1); by.setdefault(i, set()).add(e)
    order = sorted(fused, key=lambda i: -fused[i])
    return terms, per, order, by, dt

def show(raw, engines=("LITERAL", "MORPH"), scope="MATN", n=3):
    terms, per, order, by, dt = run(raw, engines, scope)
    print(f"\n■ «{raw}» [{'+'.join(engines)} / {scope}] — {len(order)} نتيجة في {dt*1000:.0f} ms")
    for t in terms: print(f"   {t[0]} → {t[1]}{' (مستبعد)' if t[2] else ''}")
    for i in order[:n]:
        r = db.execute("SELECT b.title, h.hadith_num, h.matn FROM hadiths h JOIN books b ON b.id=h.book_id WHERE h.id=?", (i,)).fetchone()
        m = zlib.decompress(r[2]).decode()
        print(f"   ← [{'، '.join(sorted(by[i]))}] {r[0]} ({r[1]}): {m[:90]}…")
    return order

o1 = show("النية", ("LITERAL",))
o2 = show("النية", ("MORPH",))
o3 = show('"إنما الأعمال بالنيات"', ("LITERAL",))
o4 = show("غض البصر")
o5 = show("الصيام -رمضان", ("LITERAL",))
o6 = show("عمر بن الخطاب", ("LITERAL",), "ISNAD")
o7 = show("المسح على الخفين")
o8 = show("الرفق", ("MORPH",))

checks = [
    ("النصي لا يطابق «النية» في «بالنيات» إلا لفظًا مطابقًا", all(
        "النيه" in norm(zlib.decompress(db.execute("SELECT matn FROM hadiths WHERE id=?", (i,)).fetchone()[0]).decode()).split()
        for i in o1[:20])),
    ("الصرفي يجد «بالنيات» عند البحث عن «النية»", any(
        "بالنيات" in norm(zlib.decompress(db.execute("SELECT matn FROM hadiths WHERE id=?", (i,)).fetchone()[0]).decode())
        for i in o2[:50])),
    ("العبارة المقتبسة تعيد نتائج", len(o3) > 0),
    ("الاستبعاد يعمل: لا كلمة «رمضان» في نتائج الصيام -رمضان", all(
        "رمضان" not in norm(zlib.decompress(db.execute("SELECT matn FROM hadiths WHERE id=?", (i,)).fetchone()[0]).decode()).split()
        for i in o5[:100])),
    ("البحث في الإسناد يجد عمر بن الخطاب", len(o6) > 0),
    ("الصرفي يعطي أكثر من النصي لـ«النية»", len(o2) >= len(o1)),
]
print("\n" + "=" * 50)
bad = 0
for label, ok in checks:
    print(("✓ " if ok else "✗ ") + label); bad += (not ok)
print("=" * 50)
print("الخلاصة:", "كل الفحوص ناجحة" if not bad else f"{bad} فحص فاشل")
sys.exit(1 if bad else 0)

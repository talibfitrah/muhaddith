# -*- coding: utf-8 -*-
"""
يمرّ على نصوص أحاديث clusters.db كلها ويستخرج عبارات الحكم الواردة في النص
(كقول الترمذي: هذا حديث حسن صحيح، والحاكم: صحيح الإسناد ولم يخرجاه، وأبي حاتم: هذا حديث منكر…)
وينسبها إلى قائلها (أو مؤلف الكتاب إن لم يُسمَّ)، ويربطها بالحديث/العنقود.

  python3 extract_corpus_rulings.py --src clusters.db --work work --out work/text_rulings.jsonl
"""
import argparse, json, os, re, sqlite3, sys, time
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from common import strip_diac, grade_level
from rulings import find_rulings
from matcher import CorpusIndex

# اسم مختصر لمؤلفي الكتب (المؤلف كما في جدول books → الاسم المشهور)
AUTHOR_SHORT = {
    "محمد بن عيسى الترمذي": "الترمذي", "الحاكم النيسابوري": "الحاكم", "ابن أبي حاتم الرازي": "ابن أبي حاتم",
    "الدارقطني": "الدارقطني", "أبو الفرج ابن الجوزي": "ابن الجوزي", "ابن حجر العسقلاني": "ابن حجر",
    "ابن عساكر الدمشقي": "ابن عساكر", "الخطيب البغدادي": "الخطيب البغدادي", "أبو نعيم الأصبهاني": "أبو نعيم الأصبهاني",
    "أبو داود السجستاني": "أبو داود", "البيهقي": "البيهقي", "أبو بكر البيهقي": "البيهقي", "الطبراني": "الطبراني",
    "ابن عدي": "ابن عدي", "ابن عدي الجرجاني": "ابن عدي", "العقيلي": "العقيلي", "ابن حبان": "ابن حبان", "ابن خزيمة": "ابن خزيمة",
    "علي بن المديني": "علي بن المديني", "أحمد بن حنبل": "أحمد بن حنبل", "البزار": "البزار", "أبو يعلى الموصلي": "أبو يعلى",
    "ابن أبي شيبة": "ابن أبي شيبة", "ابن ابي شيبة": "ابن أبي شيبة", "الذهبي": "الذهبي", "أبو عبد الله الذهبي": "الذهبي",
    "الشافعي": "الشافعي", "الطحاوي": "الطحاوي", "عبد الله بن مسلم": "ابن قتيبة", "ابن الصلاح": "ابن الصلاح",
    "يوسف المزي": "المزي", "المزي": "المزي", "النسائي": "النسائي", "مسلم بن الحجاج": "مسلم", "محمد بن إسماعيل البخاري": "البخاري",
    "ابن ماجة القزويني": "ابن ماجه", "مالك بن أنس": "مالك", "الدارمي": "الدارمي", "عبد الله بن عبد الرحمن الدارمي": "الدارمي",
    "ابن المنذر": "ابن المنذر", "محمد بن إبراهيم بن المنذر": "ابن المنذر", "ابن شاهين": "ابن شاهين", "ضياء الدين المقدسي": "الضياء المقدسي",
    "ابن عبد البر": "ابن عبد البر", "الخطابي": "الخطابي", "البغوي": "البغوي", "ابن حزم": "ابن حزم", "نور الدين الهيثمي": "الهيثمي",
    "ابن الجارود": "ابن الجارود", "أبو عوانة": "أبو عوانة", "ابن الأعرابي": "ابن الأعرابي", "تمام الرازي": "تمام الرازي",
    "أبو الشيخ الأصبهاني": "أبو الشيخ الأصبهاني", "ابن بشران": "ابن بشران", "الحارث بن أبي أسامة": "الحارث بن أبي أسامة",
    "ابن أبي عاصم": "ابن أبي عاصم", "الطيالسي": "أبو داود الطيالسي", "أبو داود الطياليسي": "أبو داود الطيالسي",
    "الحميدي": "الحميدي", "إسحاق بن راهويه": "إسحاق بن راهويه", "ابن الأثير": "ابن الأثير", "ابن كثير": "ابن كثير",
    "المنذري": "المنذري", "النووي": "النووي", "ابن الملقن": "ابن الملقن", "ابن رجب": "ابن رجب", "ابن القيم": "ابن القيم",
    "ابن تيمية": "ابن تيمية", "السيوطي": "السيوطي", "ابن قتيبة": "ابن قتيبة", "أبو زرعة الرازي": "أبو زرعة الرازي",
    "أبو حاتم الرازي": "أبو حاتم الرازي", "ابن أبي الدنيا": "ابن أبي الدنيا", "الفسوي": "الفسوي", "يعقوب بن سفيان الفسوي": "الفسوي",
}

def short_author(a):
    if not a:
        return None
    a = a.strip()
    if a in AUTHOR_SHORT:
        return AUTHOR_SHORT[a]
    for k, v in AUTHOR_SHORT.items():
        if k in a:
            return v
    return a

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    db = sqlite3.connect(a.src)
    books = {r[0]: (r[1], short_author(r[2]), r[3]) for r in db.execute("select book_id, name, author, death_date from books")}
    ix = CorpusIndex(a.work)
    idx_of = {b: i for i, b in enumerate(ix.bhid)}
    out = open(a.out, "w", encoding="utf-8")
    t0 = time.time()
    n = 0; nl = 0; per_scholar = defaultdict(int)
    q = """SELECT t.bhid, t.hadith_num, t.page_num, t.text, t.clean_matn, h.cluster_id
           FROM hadith_texts t LEFT JOIN hadiths h ON h.bhid = t.bhid WHERE t.block_type IN (2,3,8)"""
    for i, (bhid, num, page, text, matn, cluster) in enumerate(db.execute(q)):
        book_id = bhid.split("_")[0]
        plain = strip_diac(text)
        # نتجاوز ما قبل المتن المقتبس (السند) قدر الإمكان: الأحكام تأتي غالبًا بعد المتن
        found = find_rulings(plain, book_id)
        if not found:
            continue
        # المداخل الطويلة (شروح وتراجم) تحوي أحكامًا على أحاديث أخرى: لا نقبل إلا ما جاء عقب المتن المقتبس
        if len(plain) > 3000:
            qpos = [m.start() for m in re.finditer('"', plain[:4000])]
            matn_end = qpos[1] if len(qpos) >= 2 else 1500
            found = [f for f in found if f[2] <= matn_end + 900]
            if not found:
                continue
        bname, author, death = books.get(book_id, (book_id, None, None))
        cl = cluster
        conf = 1.0
        if not cl:
            # نص ليس له عنقود (كتب العلل ونحوها): نطابق متنه بأقرب حديث في المتن مع درجة ثقة
            m = ix.match(matn or text, min_hits=3)
            m = [x for x in m if ix.bhid[x[0]] != bhid]
            if m and m[0][1] >= 4 and m[0][2] >= 0.25:
                idx, h, cov = m[0]
                cl = ix.cluster[idx] or None
                h2 = next((x[1] for x in m[1:] if ix.cluster[x[0]] != cl), 0)
                conf = float(round(min(1.0, 0.5 * min(1.0, cov) + 0.3 * min(1.0, h / 8) + 0.2 * (1 - h2 / h)), 2))
                nl += 1
        for phrase, speaker, s, e in found:
            sch = speaker or author or "مؤلف الكتاب"
            quote = plain[max(0, s - 110):min(len(plain), e + 40)].replace("\n", " ")
            rec = {
                "bhid": bhid, "cluster": cl, "scholar": sch, "grade": phrase, "level": grade_level(phrase),
                "source": bname, "ref": (f"حديث {num}" if num and num > 0 else "") + (f" ص{page}" if page else ""),
                "quote": "…" + quote.strip() + "…", "origin": "text", "own": speaker is None, "conf": float(conf), "matched": bool(conf < 1.0),
            }
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n += 1; per_scholar[sch] += 1
        if i % 100000 == 0:
            print(f"  {i} نص، {n} حكم، {time.time()-t0:.0f}s", flush=True)
    out.close()
    print(f"تم: {n} حكم، منها مربوط بالمطابقة {nl}، {time.time()-t0:.0f}s")
    for k, v in sorted(per_scholar.items(), key=lambda x: -x[1])[:40]:
        print(f"  {v:6d} {k}")

if __name__ == "__main__":
    main()

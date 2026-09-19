# -*- coding: utf-8 -*-
"""
يربط أحاديث الطبعات المفتوحة (hadith-api: أبو داود، الترمذي، النسائي، ابن ماجه، الموطأ)
بأحاديث المتن، ويستخرج أحكام المحققين (الألباني، زبير علي زئي، شعيب الأرنؤوط، أحمد شاكر…)
  python3 match_api.py --work work --api /home/claude/ahkam --src clusters.db --out work/api_rulings.jsonl
"""
import argparse, json, os, re, sqlite3, sys
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from matcher import CorpusIndex, N
from common import content_words, words, shingles, grade_level, norm

EDITIONS = {
    # ملف API : (book_id في المتن، اسم الكتاب المعروض)
    "abudawud": ("184", "سنن أبي داود"),
    "tirmidhi": ("195", "جامع الترمذي"),
    "nasai": ("319", "سنن النسائي (المجتبى)"),
    "ibnmajah": ("173", "سنن ابن ماجه"),
    "malik": ("19", "موطأ مالك (رواية يحيى)"),
}

SCHOLARS = {
    "Al-Albani": "الألباني",
    "Zubair Ali Zai": "زبير علي زئي",
    "Shuaib Al Arnaut": "شعيب الأرنؤوط",
    "Ahmad Muhammad Shakir": "أحمد محمد شاكر",
    "Bashar Awad Maarouf": "بشار عواد معروف",
    "Abu Ghuddah": "عبد الفتاح أبو غدة",
    "Muhammad Muhyi Al-Din Abdul Hamid": "محمد محيي الدين عبد الحميد",
    "Muhammad Fouad Abd al-Baqi": "محمد فؤاد عبد الباقي",
    "Salim al-Hilali": "سليم الهلالي",
}

# مصدر الحكم بحسب المحقق والكتاب
SOURCES = {
    ("Al-Albani", "abudawud"): "صحيح وضعيف سنن أبي داود",
    ("Al-Albani", "tirmidhi"): "صحيح وضعيف سنن الترمذي",
    ("Al-Albani", "nasai"): "صحيح وضعيف سنن النسائي",
    ("Al-Albani", "ibnmajah"): "صحيح وضعيف سنن ابن ماجه",
    ("Zubair Ali Zai", None): "تحقيق طبعة دار السلام",
    ("Shuaib Al Arnaut", None): "تحقيق طبعة الرسالة العالمية",
    ("Ahmad Muhammad Shakir", "tirmidhi"): "تحقيق جامع الترمذي (شاكر)",
    ("Bashar Awad Maarouf", "tirmidhi"): "الجامع الكبير للترمذي (تحقيق بشار)",
    ("Abu Ghuddah", "nasai"): "تحقيق سنن النسائي (أبو غدة)",
    ("Muhammad Muhyi Al-Din Abdul Hamid", "abudawud"): "تحقيق سنن أبي داود (عبد الحميد)",
    ("Muhammad Fouad Abd al-Baqi", "ibnmajah"): "تحقيق سنن ابن ماجه (عبد الباقي)",
    ("Salim al-Hilali", "malik"): "تحقيق الموطأ (الهلالي)",
}

WORD_MAP = [
    ("Sahih - Agreed Upon", "صحيح متفق عليه"), ("Agreed Upon", "متفق عليه"),
    ("Sahih Lighairihi", "صحيح لغيره"), ("Hasan Lighairihi", "حسن لغيره"),
    ("Very Daif", "ضعيف جدًّا"), ("Sahih Bukhari", "أخرجه البخاري"), ("Sahih Muslim", "أخرجه مسلم"),
    ("Bukhari And Muslim", "البخاري ومسلم"), ("Hasan Sahih", "حسن صحيح"),
    ("Isnaad", "الإسناد"), ("Isnad", "الإسناد"), ("Sanad", "الإسناد"),
    ("Sahih", "صحيح"), ("Hasan", "حسن"), ("Daif", "ضعيف"), ("Da'if", "ضعيف"),
    ("Mauquf", "موقوف"), ("Muquf", "موقوف"), ("Mawquf", "موقوف"), ("Maqtu", "مقطوع"),
    ("Shadh", "شاذ"), ("Munkar", "منكر"), ("Mawdu", "موضوع"), ("Batil", "باطل"),
    ("Mutawatir", "متواتر"), ("Mursal", "مرسل"), ("Matn", "المتن"), ("Hadith", "الحديث"),
    ("Mudtarib", "مضطرب"), ("Gharib", "غريب"), ("Jayyid", "جيد"), ("Qawi", "قوي"),
    ("Ma'lul", "معلول"), ("Mu'allal", "معلول"), ("Munqati", "منقطع"), ("Mudallas", "مدلَّس"),
    ("Layyin", "ليِّن"), ("Wahin", "واهٍ"), ("Muallaq", "معلَّق"), ("Mu'allaq", "معلَّق"),
    ("Bukhari", "البخاري"), ("Muslim", "مسلم"), ("Lighairihi", "لغيره"), ("Li Ghairihi", "لغيره"),
    ("Isnaad Sahih", "الإسناد صحيح"), ("Marfu", "مرفوع"), ("Ziyadah", "زيادة"), ("Maudu", "موضوع"),
    ("Sahih al-Isnaad", "صحيح الإسناد"), ("Hasan al-Isnaad", "حسن الإسناد"),
    ("After", "بعد"), ("Mukhtasar", "مختصرًا"), ("Malool", "معلول"), ("Through Last", "في آخره"),
    ("Sahih Isnaad", "صحيح الإسناد"), ("Hasan Isnaad", "حسن الإسناد"), ("Daif Isnaad", "ضعيف الإسناد"),
]

def translate_grade(g):
    g = g.strip()
    if g in ("", "-"):
        return None
    out = g
    for en, ar in WORD_MAP:
        out = re.sub(r"(?i)\b" + re.escape(en) + r"\b", ar, out)
    # أرقام المصادر مثل (1023) تبقى
    out = re.sub(r"\s+", " ", out).strip(" .-")
    # ترتيب "الإسناد صحيح" → "صحيح الإسناد" و"الإسناد حسن" → "حسن الإسناد"
    out = out.replace("الإسناد صحيح", "صحيح الإسناد").replace("الإسناد حسن", "حسن الإسناد").replace("الإسناد ضعيف", "ضعيف الإسناد")
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--api", required=True)
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    ix = CorpusIndex(a.work)
    db = sqlite3.connect(a.src)
    out = open(a.out, "w", encoding="utf-8")
    stats = defaultdict(int)
    untranslated = defaultdict(int)
    for ed, (book_id, book_name) in EDITIONS.items():
        data = json.load(open(os.path.join(a.api, f"ara-{ed}.json"), encoding="utf-8"))["hadiths"]
        # فهرس احتياطي على النص الكامل (سند+متن) لكتاب واحد: شظايا 3 كلمات بلا حذف كلمات الرواية
        full = {}
        for bhid, text in db.execute(
            "select t.bhid, t.text from hadith_texts t join hadiths h on h.bhid=t.bhid where h.book_id=? and t.block_type in (2,3,8)", (book_id,)):
            full[bhid] = set(h for h, _ in shingles(words(text), 3))
        # 1) مطابقة بالمتن
        assign = {}; conf_of = {}
        for k, h in enumerate(data):
            m = ix.match(h["text"], book=book_id)
            if m and (m[0][1] >= 4 or (m[0][1] >= 3 and (len(m) == 1 or m[0][1] > m[1][1]))):
                assign[k] = ix.bhid[m[0][0]]
                idx, hh, cov = m[0]; h2 = m[1][1] if len(m) > 1 else 0
                conf_of[k] = round(min(1.0, 0.5 * min(1.0, cov) + 0.3 * min(1.0, hh / 8) + 0.2 * (1 - h2 / hh)), 2)
        # 2) الباقي: مطابقة بالنص الكامل (جاكار على شظايا 3 كلمات)
        for k, h in enumerate(data):
            if k in assign:
                continue
            q = set(x for x, _ in shingles(words(h["text"]), 3))
            if len(q) < 3:
                continue
            best, bs = None, 0.0
            for bhid, s in full.items():
                inter = len(q & s)
                if inter < 3:
                    continue
                j = inter / min(len(q), len(s))
                if j > bs:
                    best, bs = bhid, j
            if best and bs >= 0.5:
                assign[k] = best; conf_of[k] = round(0.5 + 0.5 * bs, 2)
        # 3) استكمال بالترتيب: إن كان السابق واللاحق متتاليين في المتن
        by_bhid_num = {}
        for k, b in assign.items():
            by_bhid_num[k] = int(b.split("_")[1])
        for k, h in enumerate(data):
            if k in assign or k == 0 or k + 1 >= len(data):
                continue
            p, n = by_bhid_num.get(k - 1), by_bhid_num.get(k + 1)
            if p is not None and n is not None and n - p == 2:
                cand = f"{book_id}_{p+1}"
                if cand in full and cand not in assign.values():
                    assign[k] = cand; conf_of[k] = 0.6
                    stats[ed + " (بالترتيب)"] += 1
        stats[ed + " مطابَق"] = len(assign)
        stats[ed + " كل"] = len(data)
        cluster_of = dict(db.execute("select bhid, cluster_id from hadiths where book_id=?", (book_id,)).fetchall())
        for k, h in enumerate(data):
            bhid = assign.get(k)
            if not bhid:
                continue
            for g in h.get("grades", []):
                name = g["name"].strip()
                ar = translate_grade(g["grade"])
                if not ar:
                    continue
                if re.search(r"[A-Za-z]", ar):
                    untranslated[g["grade"]] += 1
                sch = SCHOLARS.get(name, name)
                src = SOURCES.get((name, ed)) or SOURCES.get((name, None)) or f"تحقيق {book_name}"
                rec = {
                    "bhid": bhid, "cluster": cluster_of.get(bhid), "scholar": sch, "grade": ar,
                    "level": grade_level(ar), "source": f"{src} — {book_name}",
                    "ref": f"رقم {h['hadithnumber']} (ترقيم sunnah.com)", "origin": "api", "conf": conf_of.get(k, 0.9),
                    "book": book_name,
                }
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                stats["أحكام"] += 1
    out.close()
    for k, v in stats.items():
        print(k, v)
    if untranslated:
        print("غير مترجم:", sorted(untranslated.items(), key=lambda x: -x[1])[:30])

if __name__ == "__main__":
    main()

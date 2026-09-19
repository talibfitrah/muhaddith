# -*- coding: utf-8 -*-
"""أدوات مشتركة لبناء حزمة الأحكام ومختلف الحديث (تطبيع النصوص، الشظايا، تصنيف الأحكام)."""
import re, hashlib, unicodedata

DIAC = re.compile("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
NONAR = re.compile("[^\u0621-\u064A\\s]")
SPACES = re.compile(r"\s+")
TR = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي", "ء": ""})

# كلمات صيغ الرواية الشائعة لا تدخل في الشظايا (تكثر في كل حديث فتفسد التمييز)
STOP = set("""قال قالت رسول الله صلى عليه وسلم النبي عن ابن بن أبي أبو حدثنا حدثني أخبرنا أخبرني ثنا نا أنا
سمعت يقول أن أنه إن ما من في على إلى لا و يا هذا هذه ذلك الذي التي ثم أو حتى كان كانت له لها لهم فقال فقالت
قد لم لن إذا إذ عز وجل تعالى رضي عنه عنها عنهم بن ابن""".split())
STOP = {w.translate(TR) for w in STOP}


def strip_diac(s):
    return DIAC.sub("", s)


def norm(s):
    """تطبيع للمقارنة: حذف التشكيل والترقيم وتوحيد الهمزات والياء والتاء المربوطة."""
    s = strip_diac(s)
    s = s.translate(TR)
    s = NONAR.sub(" ", s)
    return SPACES.sub(" ", s).strip()


def words(s):
    return [w for w in norm(s).split(" ") if w]


def content_words(s):
    """كلمات المتن بعد حذف أل التعريف وكلمات الرواية الشائعة والكلمات القصيرة جدًّا."""
    out = []
    for w in words(s):
        if w in STOP:
            continue
        if w.startswith("ال") and len(w) > 4:
            w = w[2:]
        elif w.startswith("وال") and len(w) > 5:
            w = w[3:]
        elif w.startswith("و") and len(w) > 4:
            w = w[1:]
        if len(w) < 3:
            continue
        out.append(w)
    return out


def h64(s):
    return int.from_bytes(hashlib.blake2b(s.encode("utf-8"), digest_size=8).digest(), "little", signed=True)


def shingles(ws, n=4):
    """شظايا n-كلمات مع موضع بدايتها في قائمة الكلمات."""
    return [(h64(" ".join(ws[i:i + n])), i) for i in range(0, len(ws) - n + 1)]


# ---------- تصنيف نص الحكم إلى درجة رقمية (كما في أحكام المحدِّث: 0 صحيح … 5 موضوع) ----------
LEVELS = {0: "صحيح", 1: "حسن", 2: "ضعيف", 3: "ضعيف جدًّا", 4: "متهم/باطل", 5: "موضوع"}

def grade_level(text):
    t = norm(text)
    if "موضوع" in t or "مكذوب" in t or "كذب" in t:
        return 5
    if "باطل" in t or "متهم" in t:
        return 4
    if "ضعيف جدا" in t or "واه" in t or "ساقط" in t or "منكر جدا" in t or "تالف" in t:
        return 3
    if "لا يصح" in t or "لا يثبت" in t or "ضعيف" in t or "منكر" in t or "لا اصل" in t or "معلول" in t or "شاذ" in t or "وهم" in t or "خطا" in t:
        return 2
    if "حسن" in t and "صحيح" not in t:
        return 1
    if "صحيح" in t or "ثقات" in t or "علي شرط" in t or "متفق" in t or "جيد" in t or "قوي" in t or "ثابت" in t:
        return 0
    return None

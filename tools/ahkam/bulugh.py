# -*- coding: utf-8 -*-
"""
بلوغ المرام لابن حجر (نص sunnah.com بتحقيق سمير الزهيري): أحكام ابن حجر نفسه، وما نقله عن الأئمة
(«وصححه ابن خزيمة والترمذي»، «وضعفه أبو حاتم»…)، وحكم المحقق في الحاشية.
  python3 bulugh.py --json bulugh_almaram.json --work work --out work/bulugh_rulings.jsonl
"""
import argparse, json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from matcher import CorpusIndex
from common import strip_diac, grade_level
from rulings import find_rulings, canon

FOOT_SPLIT = re.compile(r"‏*\s*1\s*‏*\s*-\s")
CITE = re.compile(r"(?:^|\s)و?(صححه|ضعفه|حسنه|أعله|قواه|صوبه|استغربه|وهاه|أنكره|ضعفوه|صححوه|أعلوه|رجح إرساله|رجح وقفه|استنكره|جوده|أعلها|صححها|ضعفها|حسنها)\s+([ء-ي][ء-ي ]{1,60}?)(?=\s*(?:[،,.;:(\d‏{}]|و(?:صحح|ضعف|حسن|أعل|قوا|صوب|استغرب|وها|أنكر|رجح|رواه|أخرجه|اللفظ|هو|هي|في|له|لها|إسناده|سنده|قال)|$))")
VERB_GRADE = {
    "صححه": ("صحيح", 0), "صححوه": ("صحيح", 0), "صححها": ("صحيح", 0), "حسنه": ("حسن", 1), "حسنها": ("حسن", 1),
    "ضعفه": ("ضعيف", 2), "ضعفوه": ("ضعيف", 2), "ضعفها": ("ضعيف", 2), "أعله": ("معلول", 2), "أعلوه": ("معلول", 2), "أعلها": ("معلول", 2),
    "قواه": ("قوي", 0), "جوده": ("جيد", 0), "صوبه": ("صوّبه", 0), "استغربه": ("غريب", None), "وهاه": ("واهٍ", 3),
    "أنكره": ("منكر", 2), "استنكره": ("منكر", 2), "رجح إرساله": ("مرسل (رجّح إرساله)", 2), "رجح وقفه": ("موقوف (رجّح وقفه)", None),
}
EDITOR_GRADE = re.compile(r"^\s*(صحيح|حسن|ضعيف|موضوع|صحيح لغيره|حسن لغيره|ضعيف جدا|ضعيف جدًا|حسن صحيح|صحيح بشواهده|حسن بشواهده|منكر|باطل|شاذ|لا أصل له|مرسل|موقوف|صحيح موقوفا|صحيح مرفوعا|حسن موقوفا)\b")

def split_names(s):
    s = s.strip(" ،,.")
    parts = re.split(r"\s+و(?=[ء-ي])", " " + s)
    out = []
    for p in parts:
        p = p.strip()
        if p and len(p) <= 30:
            out.append(p)
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    ix = CorpusIndex(a.work)
    hs = json.load(open(a.json, encoding="utf-8"))["hadiths"]
    out = open(a.out, "w", encoding="utf-8")
    n = nm = 0
    unknown = {}
    for h in hs:
        t = h["arabic"]
        m = FOOT_SPLIT.search(t)
        main, foot = (t[:m.start()], t[m.start():]) if m else (t, "")
        main = strip_diac(main).replace("‏", "")
        foot = strip_diac(foot).replace("‏", "")
        # المتن المقتبس بين { }
        quotes = re.findall(r"\{([^{}]{12,})\}", main)
        qtext = " ".join(quotes) if quotes else main
        mt = ix.match(qtext, min_hits=3)
        cluster = None; via = None; conf = 0.0
        if mt and mt[0][1] >= 4 and mt[0][2] >= 0.25:
            idx, hh, cov = mt[0]
            cluster = ix.cluster[idx] or None; via = ix.bhid[idx]
            h2 = next((x[1] for x in mt[1:] if ix.cluster[x[0]] != cluster), 0)
            conf = round(min(1.0, 0.5 * min(1.0, cov) + 0.3 * min(1.0, hh / 8) + 0.2 * (1 - h2 / hh)), 2)
        if not cluster:
            continue
        nm += 1
        num = h.get("idInBook")
        base = {"cluster": cluster, "bhid": None, "via": via, "origin": "bulugh", "source": "بلوغ المرام لابن حجر",
                "ref": f"حديث {num}", "conf": conf}
        ctx = "…" + re.sub(r"\s+", " ", main)[-220:].strip() + "…"
        # 1) أحكام ابن حجر نفسه
        for phrase, speaker, s, e in find_rulings(main, None):
            rec = dict(base, scholar=speaker or "ابن حجر", grade=phrase, level=grade_level(phrase),
                       quote="…" + main[max(0, s - 100):e + 30].strip() + "…")
            out.write(json.dumps(rec, ensure_ascii=False) + "\n"); n += 1
        if re.search(r"(?<![ء-ي])متفق عليه", main):
            rec = dict(base, scholar="ابن حجر", grade="متفق عليه", level=0, quote=ctx)
            out.write(json.dumps(rec, ensure_ascii=False) + "\n"); n += 1
        # 2) ما نقله عن الأئمة
        for vm in CITE.finditer(main):
            verb, names = vm.group(1), vm.group(2)
            g, lvl = VERB_GRADE[verb]
            for nm_ in split_names(names):
                sch = canon(nm_)
                if sch is None or len(sch) > 30:
                    unknown[nm_] = unknown.get(nm_, 0) + 1
                    continue
                rec = dict(base, scholar=sch, grade=f"{g} (بنقل ابن حجر: {verb} {nm_})", level=lvl,
                           quote="…" + main[max(0, vm.start() - 90):vm.end() + 20].strip() + "…")
                out.write(json.dumps(rec, ensure_ascii=False) + "\n"); n += 1
        # 3) حكم المحقق في الحاشية
        fm = EDITOR_GRADE.match(re.sub(r"^\s*1\s*-\s*", "", foot))
        if fm:
            g = fm.group(1)
            rec = dict(base, scholar="سمير الزهيري (محقق بلوغ المرام)", grade=g, level=grade_level(g),
                       source="بلوغ المرام، تحقيق سمير الزهيري (دار الفلق)", quote="…" + foot[:160].strip() + "…")
            out.write(json.dumps(rec, ensure_ascii=False) + "\n"); n += 1
    out.close()
    print(f"مربوط {nm} من {len(hs)}، أحكام {n}")
    print("أسماء غير معروفة:", sorted(unknown.items(), key=lambda x: -x[1])[:40])

if __name__ == "__main__":
    main()

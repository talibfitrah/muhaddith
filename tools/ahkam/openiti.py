# -*- coding: utf-8 -*-
"""قراءة نصوص OpenITI (mARkdown) وتقسيمها إلى مقاطع مع أرقام الأجزاء والصفحات."""
import re, os

META_RE = re.compile(r"^#META#\s*(\S+)\s*::\s*(.*)$")
PAGE_RE = re.compile(r"PageV(\d+)P(\d+)")
PS_RE = re.compile(r"\[ص:\s*(\d+)\]")
MS_RE = re.compile(r"\bms\d+\b")
FOOT_RE = re.compile(r"(?<=[ء-يً-ْ])\d{1,2}(?=[\s،,.:؛)\]»\"]|$)")  # أرقام الحواشي الملتصقة بالكلمات
HDR_RE = re.compile(r"^###\s*(\|+)\s*(.*)$")
NUM_HDR = re.compile(r"^\d+\s*-\s*(?:/\s*\d+\s*-\s*)?$")
INLINE_HDR = re.compile(r"^(?:باب|كتاب|فصل)\s+[^.:؛]{2,80}")


def read_meta(path):
    meta = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#META#Header#End#"):
                break
            m = META_RE.match(line.strip())
            if m and m.group(2) not in ("NODATA", "NOTGIVEN"):
                meta[m.group(1)] = m.group(2)
    return meta


HTML_RE = re.compile(r"</?[A-Za-z][^<>]{0,120}>|&nbsp;|&amp;|&lt;|&gt;|&quot;")
def clean_para(s):
    s = HTML_RE.sub(" ", s)   # وسوم HTML من نصوص الشاملة (span class="matn" …)
    s = MS_RE.sub("", s)
    s = PAGE_RE.sub("", s)
    s = PS_RE.sub("", s)
    s = FOOT_RE.sub("", s)
    s = s.replace("~~", " ")
    s = re.sub(r"\bCHECK\b|@[A-Z]+@|%~%", "", s)
    return re.sub(r"[ \t]+", " ", s).strip()


def parse(path, target_words=350, max_words=700):
    """يعيد قائمة مقاطع: dict(title, vol, page_start, page_end, text, ord)."""
    sections = []
    cur = {"title": "", "vol": None, "page_start": None, "page_end": None, "paras": []}
    vol, page = None, None
    started = False
    pending_title = ""

    def flush():
        nonlocal cur
        if cur["paras"]:
            text = "\n".join(p for p in cur["paras"] if p)
            if text.strip():
                sections.append({"title": cur["title"], "vol": cur["vol"], "page_start": cur["page_start"],
                                 "page_end": cur["page_end"], "text": text})
        cur = {"title": cur["title"], "vol": vol, "page_start": page, "page_end": page, "paras": []}

    def note_page(line):
        nonlocal vol, page
        for m in PAGE_RE.finditer(line):
            v, p = int(m.group(1)), int(m.group(2))
            if v or p:
                vol, page = (v or vol), p
        for m in PS_RE.finditer(line):
            page = int(m.group(1))
        if cur["page_start"] is None:
            cur["page_start"] = page
            cur["vol"] = vol
        cur["page_end"] = page

    with open(path, encoding="utf-8") as f:
        buf = ""
        for raw in f:
            line = raw.rstrip("\n")
            if not started:
                if line.startswith("#META#Header#End#"):
                    started = True
                continue
            if not line.strip():
                continue
            hm = HDR_RE.match(line)
            if hm:
                title = clean_para(hm.group(2))
                if PS_RE.search(hm.group(2)) and not title:
                    note_page(hm.group(2)); continue
                note_page(line)
                # عنوان مقطع جديد
                if buf:
                    cur["paras"].append(clean_para(buf)); buf = ""
                flush()
                cur["title"] = title
                cur["vol"], cur["page_start"], cur["page_end"] = vol, page, page
                continue
            if line.startswith("# ") or line.startswith("#"):
                if buf:
                    cur["paras"].append(clean_para(buf))
                buf = line.lstrip("#").strip()
                note_page(line)
                # «باب …» / «كتاب …» في أول الفقرة يبدأ مقطعًا جديدًا
                bm = INLINE_HDR.match(clean_para(buf))
                if bm and len(cur["paras"]) > 0:
                    flush()
                    cur["title"] = bm.group(0)[:90]
                    cur["vol"], cur["page_start"], cur["page_end"] = vol, page, page
            elif line.startswith("~~"):
                buf += " " + line[2:].strip()
                note_page(line)
            else:
                note_page(line)
                s = line.strip()
                if s and not PAGE_RE.fullmatch(s):
                    buf += " " + s
            # تقسيم المقاطع الطويلة جدًّا
            if sum(len(p.split()) for p in cur["paras"]) > max_words:
                cur["paras"].append(clean_para(buf)); buf = ""
                flush()
        if buf:
            cur["paras"].append(clean_para(buf))
        flush()
    # دمج المقاطع القصيرة جدًّا مع ما بعدها (العناوين الرقمية المجرّدة ونحوها)
    merged = []
    for s in sections:
        if merged and len(merged[-1]["text"].split()) < 40 and (not merged[-1]["title"] or NUM_HDR.match(merged[-1]["title"])):
            prev = merged.pop()
            s = {"title": prev["title"] or s["title"], "vol": prev["vol"] or s["vol"],
                 "page_start": prev["page_start"] or s["page_start"], "page_end": s["page_end"] or prev["page_end"],
                 "text": (prev["text"] + "\n" + s["text"]).strip()}
        merged.append(s)
    for i, s in enumerate(merged):
        s["ord"] = i
    return merged


if __name__ == "__main__":
    import sys
    secs = parse(sys.argv[1])
    print(len(secs), "مقطع")
    for s in secs[:6] + secs[len(secs)//2: len(secs)//2 + 3]:
        print("---", s["title"][:80], s["vol"], s["page_start"], s["page_end"], len(s["text"].split()))
        print(s["text"][:300].replace("\n", " ⏎ "))

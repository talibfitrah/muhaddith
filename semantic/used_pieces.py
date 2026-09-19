# -*- coding: utf-8 -*-
"""يجمع قطع المفردات المستعملة فعلًا في تقطيع المتون والأسانيد والأطراف كلها (لتقليم مصفوفة التضمين)."""
import sqlite3, zlib, re, json, time
from transformers import AutoTokenizer
DIAC = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
tok = AutoTokenizer.from_pretrained("e5-tok")
db = sqlite3.connect("file:/home/claude/muhaddith-data/muhaddith_corpus_full.db?mode=ro", uri=True)
used = set(); t0 = time.time(); n = 0
batch = []
def flush():
    global batch
    if not batch: return
    for ids in tok(batch, add_special_tokens=False)["input_ids"]: used.update(ids)
    batch = []
for (m, s) in db.execute("SELECT matn, sanad FROM hadiths"):
    batch.append(DIAC.sub("", zlib.decompress(m).decode()))
    if s: batch.append(DIAC.sub("", zlib.decompress(s).decode()))
    n += 1
    if len(batch) >= 2000: flush()
    if n % 100000 == 0: print("  ", n, len(used), flush=True)
flush()
for (t,) in db.execute("SELECT taraf FROM clusters"): 
    if t: batch.append(t)
for (nm,) in db.execute("SELECT name FROM rawis"): batch.append(nm)
for (nm,) in db.execute("SELECT title FROM books"): batch.append(nm)
flush()
# كلمات استعلام شائعة بالعربية والإنجليزية + أرقام
extra = "الله محمد رسول صلى عليه وسلم النبي القرآن الحديث الصلاة الزكاة الصوم الحج الجهاد العلم الإيمان التوحيد الشرك البدعة السنة الفقه التفسير العقيدة الأخلاق الآداب الدعاء الذكر التوبة الجنة النار القيامة الموت القبر الملائكة الجن الشيطان الرزق التجارة البيع الربا النكاح الطلاق الميراث القضاء الحدود الطهارة الوضوء الغسل التيمم الجمعة العيد رمضان الحج العمرة hello world search hadith prophet islam".split()
print("extra:", len(extra))
used.update(tok(" ".join(extra), add_special_tokens=False)["input_ids"])
used.update([0,1,2,3])
json.dump(sorted(int(i) for i in used), open("used_pieces.json","w"))
print("used pieces:", len(used), "of", len(tok), "in %.0fs" % (time.time()-t0))

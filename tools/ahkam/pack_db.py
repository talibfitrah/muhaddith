# -*- coding: utf-8 -*-
"""يضغط قاعدة حزمة (أحكام/شروح) ويقسمها أجزاءً ≤ ٢٩ م.ب، ويحدّث ثوابت KnownPacks.kt وmanifest.json
  python3 pack_db.py --db muhaddith_shuruh.db --id shuruh --const SHURUH --name "شروح الحديث وأسباب وروده" --out parts --manifest manifest.json --known KnownPacks.kt
"""
import argparse, gzip, hashlib, json, os, re, sqlite3, subprocess

AR = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")
def ar(x):
    v = f"{int(x):,}".replace(",", "٬") if str(x).isdigit() else str(x)
    return v.translate(AR)

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True); ap.add_argument("--id", required=True); ap.add_argument("--const", required=True)
    ap.add_argument("--name", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--manifest", required=True); ap.add_argument("--known", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    name = f"muhaddith_{a.id}.db.gz"
    gz = os.path.join(os.path.dirname(os.path.abspath(a.db)), name)
    # mtime=0: ضغط حتميّ — الملف نفسه يعطي البصمة نفسها في كل مرة (وإلا عُدّ تحديثًا زائفًا عند المستخدمين)
    with open(a.db, "rb") as i, open(gz, "wb") as raw, gzip.GzipFile(filename="", mode="wb", compresslevel=9, fileobj=raw, mtime=0) as o:
        while True:
            b = i.read(1 << 20)
            if not b: break
            o.write(b)
    for f in os.listdir(a.out):
        if f.startswith(name + "."): os.remove(os.path.join(a.out, f))
    subprocess.run(["split", "-b", "29m", "-d", "-a", "3", "--numeric-suffixes=1", gz, os.path.join(a.out, name + ".")], check=True)
    parts = sorted(f for f in os.listdir(a.out) if f.startswith(name + "."))
    raw = dict(sqlite3.connect(a.db).execute("select key, value from meta").fetchall())
    if a.id == "shuruh":
        desc = f"{ar(raw.get('books_count', 0))} كتابًا في الشروح وأسباب الورود ({ar(raw.get('sections_count', 0))} مقطعًا)، و{ar(raw.get('points_count', 0))} استنباطًا لـ{ar(raw.get('scholars_count', 0))} عالمًا على {ar(raw.get('clusters_with_points', 0))} حديثًا، و{ar(raw.get('stories_count', 0))} رواية سياق"
    else:
        desc = f"{ar(raw.get('rulings_count', 0))} حكمًا من {ar(raw.get('scholars_count', 0))} عالمًا، و{ar(raw.get('books_count', 0))} كتابًا ({ar(raw.get('sections_count', 0))} مقطعًا)"
    info = {"id": a.id, "name": a.name, "description": desc, "file": name, "size_gz": os.path.getsize(gz), "size_db": os.path.getsize(a.db),
            "sha256_gz": sha(gz), "hadiths": int(raw.get("hadiths_with_rulings", raw.get("clusters_with_points", 0)) or 0), "books": int(raw.get("books_count", 0) or 0),
            "kind": a.id, "parts": parts, "built": raw.get("built", ""), "schema": raw.get("schema_version", "")}
    m = json.load(open(a.manifest, encoding="utf-8"))
    m["packs"] = [p for p in m["packs"] if p["id"] != a.id]
    idx = max([i for i, p in enumerate(m["packs"]) if p["id"].startswith("corpus") or p["id"] in ("ahkam",)] + [-1]) + 1
    m["packs"].insert(idx, info)
    json.dump(m, open(a.manifest, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    k = open(a.known, encoding="utf-8").read()
    C = a.const
    const = (f'    const val {C}_PARTS = {len(parts)}; const val {C}_GZ = {info["size_gz"]}L; '
             f'const val {C}_RAW = {info["size_db"]}L; const val {C}_SHA = "{info["sha256_gz"]}"')
    k = re.sub(r"(// @@" + C + r"_CONSTANTS@@\n).*?(\n    // @@END_" + C + r"_CONSTANTS@@)", lambda mm: mm.group(1) + const + mm.group(2), k, flags=re.S)
    open(a.known, "w", encoding="utf-8").write(k)
    print(json.dumps(info, ensure_ascii=False, indent=1))

if __name__ == "__main__":
    main()

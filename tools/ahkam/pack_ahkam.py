# -*- coding: utf-8 -*-
"""يضغط muhaddith_ahkam.db ويقسمه أجزاءً ≤ ٢٩ م.ب، ويحدّث ثوابت KnownPacks.kt وmanifest.json
  python3 pack_ahkam.py --db muhaddith_ahkam.db --out parts/ --manifest manifest.json --known KnownPacks.kt
"""
import argparse, gzip, hashlib, json, os, re, sqlite3, subprocess

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--manifest", required=True); ap.add_argument("--known", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    name = "muhaddith_ahkam.db.gz"
    gz = os.path.join(os.path.dirname(os.path.abspath(a.db)), name)
    with open(a.db, "rb") as i, gzip.open(gz, "wb", compresslevel=9) as o:
        while True:
            b = i.read(1 << 20)
            if not b: break
            o.write(b)
    for f in os.listdir(a.out):
        if f.startswith(name + "."): os.remove(os.path.join(a.out, f))
    subprocess.run(["split", "-b", "29m", "-d", "-a", "3", "--numeric-suffixes=1", gz, os.path.join(a.out, name + ".")], check=True)
    parts = sorted(f for f in os.listdir(a.out) if f.startswith(name + "."))
    meta = dict(sqlite3.connect(a.db).execute("select key, value from meta").fetchall())
    AR = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")
    def ar(x):
        v = f"{int(x):,}".replace(",", "٬") if str(x).isdigit() else str(x)
        return v.translate(AR)
    meta = {k: ar(v) if k.endswith("_count") or k.endswith("_rulings") else v for k, v in meta.items()}
    raw = dict(sqlite3.connect(a.db).execute("select key, value from meta").fetchall())
    info = {"id": "ahkam", "name": "أحكام العلماء ومختلف الحديث",
            "description": f"{meta.get('rulings_count')} حكمًا من {meta.get('scholars_count')} عالمًا، و{meta.get('books_count')} كتابًا في مختلف الحديث والناسخ والمنسوخ والتخريج والعلل ({meta.get('sections_count')} مقطعًا)",
            "file": name, "size_gz": os.path.getsize(gz), "size_db": os.path.getsize(a.db), "sha256_gz": sha(gz),
            "hadiths": int(raw.get("hadiths_with_rulings", 0)), "books": int(raw.get("books_count", 0)), "kind": "ahkam", "parts": parts}
    # manifest
    m = json.load(open(a.manifest, encoding="utf-8"))
    m["packs"] = [p for p in m["packs"] if p["id"] != "ahkam"]
    # نضعها بعد حزم المتون
    idx = max([i for i, p in enumerate(m["packs"]) if p["id"].startswith("corpus")] + [-1]) + 1
    m["packs"].insert(idx, info)
    json.dump(m, open(a.manifest, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    # KnownPacks.kt
    k = open(a.known, encoding="utf-8").read()
    const = (f'    const val AHKAM_PARTS = {len(parts)}; const val AHKAM_GZ = {info["size_gz"]}L; '
             f'const val AHKAM_RAW = {info["size_db"]}L; const val AHKAM_SHA = "{info["sha256_gz"]}"')
    k = re.sub(r"(// @@AHKAM_CONSTANTS@@\n).*?(\n    // @@END_AHKAM_CONSTANTS@@)", lambda mm: mm.group(1) + const + mm.group(2), k, flags=re.S)
    open(a.known, "w", encoding="utf-8").write(k)
    json.dump(info, open(os.path.join(os.path.dirname(os.path.abspath(a.db)), "ahkam_pack.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(info, ensure_ascii=False, indent=1))

if __name__ == "__main__":
    main()

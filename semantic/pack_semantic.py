# -*- coding: utf-8 -*-
"""يحزم حزم البحث الدلالي للهاتف: حاوية النموذج (MSBP) ومتجهات كل حزمة (MSBV v2 بوسم الحزمة)،
ثم يضغطها gzip ويقسمها أجزاءً ≤ ٢٩ م.ب، ويكتب semantic_packs.json ويحدّث KnownPacks.kt وmanifest.json."""
import struct, gzip, hashlib, json, os, sqlite3, subprocess, sys, numpy as np

OUT = "/home/claude/muhaddith-data/parts"
DATA = "/home/claude/muhaddith-data"
os.makedirs(OUT, exist_ok=True)

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def gz_and_split(src, name):
    gz = os.path.join(DATA, name)
    with open(src, "rb") as i, gzip.open(gz, "wb", compresslevel=6) as o:
        while True:
            b = i.read(1 << 20)
            if not b: break
            o.write(b)
    for f in os.listdir(OUT):
        if f.startswith(name + "."): os.remove(os.path.join(OUT, f))
    subprocess.run(["split", "-b", "29m", "-d", "-a", "3", "--numeric-suffixes=1", gz, os.path.join(OUT, name + ".")], check=True)
    parts = sorted(f for f in os.listdir(OUT) if f.startswith(name + "."))
    return {"file": name, "size_gz": os.path.getsize(gz), "size_db": os.path.getsize(src), "sha256_gz": sha(gz), "parts": parts}

def model_container(out):
    entries = [("model.onnx", "bge-onnx/bge_int8.onnx"), ("vocab.bin", "bge-onnx/bge_vocab.bin"), ("model.json", "bge-onnx/model.json")]
    with open(out, "wb") as f:
        f.write(b"MSBP"); f.write(struct.pack("<I", len(entries)))
        for name, path in entries:
            nb = name.encode(); f.write(struct.pack("<H", len(nb))); f.write(nb)
            f.write(struct.pack("<Q", os.path.getsize(path)))
            with open(path, "rb") as i:
                while True:
                    b = i.read(1 << 20)
                    if not b: break
                    f.write(b)

def convert_vectors(src, dst, dataset, remap_db=None, src_db=None):
    """MSBV v1 → v2 مع وسم الحزمة؛ وإن أُعطيت قاعدة أخرى تُحوَّل المعرّفات عبر bhid"""
    with open(src, "rb") as f:
        magic = f.read(4); ver, dim, n = struct.unpack("<III", f.read(12))
        if ver >= 2: f.read(16)
        ids = np.frombuffer(f.read(n * 4), dtype=np.int32).copy()
        scales = np.frombuffer(f.read(n * 4), dtype=np.float32).copy()
        vecs = np.frombuffer(f.read(n * dim), dtype=np.int8).reshape(n, dim).copy()
    if remap_db:
        a = sqlite3.connect(f"file:{src_db}?mode=ro", uri=True); b = sqlite3.connect(f"file:{remap_db}?mode=ro", uri=True)
        id2bhid = dict(a.execute("SELECT id, bhid FROM hadiths"))
        bhid2id = dict(b.execute("SELECT bhid, id FROM hadiths"))
        new = np.array([bhid2id.get(id2bhid.get(int(i)), -1) for i in ids], dtype=np.int32)
        keep = new >= 0
        ids, scales, vecs = new[keep], scales[keep], vecs[keep]
        print(f"  remapped {keep.sum()}/{n} ids to {os.path.basename(remap_db)}")
        n = len(ids)
    o = np.argsort(ids, kind="stable"); ids, scales, vecs = ids[o], scales[o], vecs[o]
    with open(dst, "wb") as f:
        f.write(b"MSBV"); f.write(struct.pack("<III", 2, dim, n)); f.write(dataset.encode().ljust(16, b"\0"))
        f.write(ids.tobytes()); f.write(scales.tobytes()); f.write(vecs.tobytes())
    print(f"  wrote {dst}: {n} vectors, {os.path.getsize(dst)/1e6:.1f} MB")

def main():
    which = sys.argv[1:] or ["model", "tisa", "full"]
    info = json.load(open("semantic_packs.json")) if os.path.exists("semantic_packs.json") else {}
    if "model" in which:
        model_container("muhaddith_model_bge.bin")
        info["model_bge"] = gz_and_split("muhaddith_model_bge.bin", "muhaddith_model_bge.bin.gz")
    if "tisa" in which:
        info["vectors_tisa"] = gz_and_split("muhaddith_vectors_tisa.bin", "muhaddith_vectors_tisa.bin.gz")
    if "full" in which:
        info["vectors_full"] = gz_and_split("muhaddith_vectors_full.bin", "muhaddith_vectors_full.bin.gz")
    json.dump(info, open("semantic_packs.json", "w"), indent=1)
    print(json.dumps({k: (v["size_gz"], len(v["parts"])) for k, v in info.items()}))

    # تحديث KnownPacks.kt
    kp = "/home/claude/muhaddith-android/app/src/main/java/org/murabbie/muhaddith/data/KnownPacks.kt"
    s = open(kp, encoding="utf-8").read()
    def c(k): return info.get(k, {"parts": [], "size_gz": 0, "size_db": 0, "sha256_gz": ""})
    m, t, f = c("model_bge"), c("vectors_tisa"), c("vectors_full")
    block = f'''    // @@SEMANTIC_CONSTANTS@@
    const val MODEL_PARTS = {len(m["parts"])}; const val MODEL_GZ = {m["size_gz"]}L; const val MODEL_RAW = {m["size_db"]}L; const val MODEL_SHA = "{m["sha256_gz"]}"
    const val VT_PARTS = {len(t["parts"])}; const val VT_GZ = {t["size_gz"]}L; const val VT_RAW = {t["size_db"]}L; const val VT_SHA = "{t["sha256_gz"]}"
    const val VF_PARTS = {len(f["parts"])}; const val VF_GZ = {f["size_gz"]}L; const val VF_RAW = {f["size_db"]}L; const val VF_SHA = "{f["sha256_gz"]}"
    // @@END_SEMANTIC_CONSTANTS@@'''
    i = s.index("    // @@SEMANTIC_CONSTANTS@@"); j = s.index("// @@END_SEMANTIC_CONSTANTS@@") + len("// @@END_SEMANTIC_CONSTANTS@@")
    open(kp, "w", encoding="utf-8").write(s[:i] + block + s[j:])

    # تحديث manifest.json
    mf = json.load(open(f"{DATA}/manifest.json", encoding="utf-8"))
    mf["packs"] = [p for p in mf["packs"] if p["id"] in ("tisa", "full", "corpus_tisa", "corpus_full")]
    for p in mf["packs"]:
        if p["id"] == "tisa": p["id"] = "corpus_tisa"
        if p["id"] == "full": p["id"] = "corpus_full"
    names = {"model_bge": ("نموذج البحث الدلالي (BGE-M3)", "نموذج سطح المكتب نفسه مكمَّمًا للهاتف", "model", 0, 0),
             "vectors_tisa": ("متجهات الكتب التسعة", "للبحث الدلالي مع حزمة الكتب التسعة", "vectors", 58684, 9),
             "vectors_full": ("متجهات الحزمة الكاملة", "للبحث الدلالي مع الحزمة الكاملة", "vectors", 463733, 1400)}
    for k, v in info.items():
        nm, desc, kind, h, b = names[k]
        mf["packs"].append({"id": k, "name": nm, "description": desc, "kind": kind, "file": v["file"], "size_gz": v["size_gz"],
                            "size_db": v["size_db"], "sha256_gz": v["sha256_gz"], "hadiths": h, "books": b, "parts": v["parts"]})
    json.dump(mf, open(f"{DATA}/manifest.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("manifest packs:", [p["id"] for p in mf["packs"]])

if __name__ == "__main__":
    main()

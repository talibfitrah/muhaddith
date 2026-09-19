# -*- coding: utf-8 -*-
"""يحوّل vectors.db (sqlite-vec، bge-m3 int8[1024]) إلى ملفات MSBV للهاتف مع تحويل bhid → معرّفات قاعدة الهاتف."""
import sqlite3, sqlite_vec, numpy as np, struct, sys, os
VDB = "/home/claude/muhaddith-data/vectors.db"
def build(corpus_db, dataset, out):
    c = sqlite3.connect(f"file:{VDB}?mode=ro", uri=True); c.enable_load_extension(True); sqlite_vec.load(c); c.enable_load_extension(False)
    h = sqlite3.connect(f"file:{corpus_db}?mode=ro", uri=True)
    bhid2id = dict(h.execute("SELECT bhid, id FROM hadiths"))
    rowid2bhid = dict(c.execute("SELECT rowid, bhid FROM hadith_vec_meta"))
    ids, vecs = [], []
    n = 0
    for rowid, emb in c.execute("SELECT rowid, embedding FROM hadith_vec"):
        hid = bhid2id.get(rowid2bhid.get(rowid))
        if hid is None: continue
        ids.append(hid); vecs.append(np.frombuffer(emb, dtype=np.int8)); n += 1
        if n % 100000 == 0: print("  ", n, flush=True)
    ids = np.array(ids, dtype=np.int32); V = np.stack(vecs)
    o = np.argsort(ids, kind="stable"); ids, V = ids[o], V[o]
    scales = np.full(len(ids), 1.0/127.0, dtype=np.float32)
    with open(out, "wb") as f:
        f.write(b"MSBV"); f.write(struct.pack("<III", 2, 1024, len(ids))); f.write(dataset.encode().ljust(16, b"\0"))
        f.write(ids.tobytes()); f.write(scales.tobytes()); f.write(V.tobytes())
    print(f"{out}: {len(ids)} vectors of {len(bhid2id)} hadiths, {os.path.getsize(out)/1e6:.1f} MB", flush=True)
build("/home/claude/muhaddith-data/muhaddith_corpus_tisa.db", "tisa", "muhaddith_vectors_tisa.bin")
build("/home/claude/muhaddith-data/muhaddith_corpus_full.db", "full", "muhaddith_vectors_full.bin")

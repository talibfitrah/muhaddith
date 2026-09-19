#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""رفع ملفات التطوير (المشروع والمصادر والأدوات والدليل) إلى /downloads/muhaddith/dev على NAS
  python3 nas_dev_upload.py --dir /مجلد/الملفات
يرفع: muhaddith-android-project.tar.gz، muhaddith-<إصدار>-play.aab، muhaddith-sources.tar.gz.*، nas_publish.py، publish.bat/.sh، HANDOVER.md، build.gradle.kts، SHA256SUMS.txt
ملاحظة: مفتاح التوقيع (app/muhaddith-release.jks) لا يُرفع إلى المجلد العام؛ احتفظ به عندك."""
import argparse, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nas_publish as n

DEV_DIR = n.REMOTE_DIR + "/dev"
PATTERNS = [r"muhaddith-android-project\.tar\.gz$", r"muhaddith-[\d.]+-play\.aab$", r"muhaddith-sources\.tar\.gz\.\d{3}$", r"nas_publish\.py$", r"nas_dev_upload\.py$", r"publish\.(bat|sh)$", r"HANDOVER\.md$", r"README\.md$", r"build\.gradle\.kts$", r"SHA256SUMS\.txt$"]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--dir", required=True); a = ap.parse_args()
    files = sorted(f for f in os.listdir(a.dir) if any(re.match(p, f) for p in PATTERNS) and os.path.isfile(os.path.join(a.dir, f)))
    sid = n.login()
    n.REMOTE_DIR = DEV_DIR   # الرفع والقوائم على المجلد الفرعي
    have = n.listing(sid)
    for i, f in enumerate(files, 1):
        p = os.path.join(a.dir, f); s = os.path.getsize(p)
        if have.get(f) == s and (n.remote_md5(sid, f"{DEV_DIR}/{f}") == n.local_md5(p)): print(f"[{n.ar(i)}/{n.ar(len(files))}] {f} موجود — تخطٍّ"); continue
        print(f"[{n.ar(i)}/{n.ar(len(files))}] {f} ({n.ar(f'{s/1048576:.1f}')} م.ب) …", end="", flush=True); t0 = time.time(); n.upload(sid, p); print(f" {n.ar(int(time.time()-t0))} ث")
    lk = n.links(sid); need = [f"{DEV_DIR}/{f}" for f in files if f"{DEV_DIR}/{f}" not in lk]
    n.make_links(sid, need); lk = n.links(sid)
    print(f"مجلد التطوير: {n.PUBLIC}/sharing/ehniFEwJg → muhaddith/dev ({n.ar(len(files))} ملفًا، روابط جديدة {n.ar(len(need))})")
    for f in files: print(f"  {f}: {n.PUBLIC}/sharing/{lk.get(f'{DEV_DIR}/{f}', '?')}")
    n.call("auth.cgi", dict(api="SYNO.API.Auth", version=3, method="logout", session="FileStation", _sid=sid))

if __name__ == "__main__": main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""نشر حزم «المحدِّث» على NAS سينولوجي عبر واجهة DSM (https://files.murabbie.org أو المنفذ ٥٠٠٠)

  python3 nas_publish.py --dir /مجلد/الملفات [--manifest manifest.json] [--only-links]

الخطوات: رفع كل muhaddith* وSHA256SUMS.txt إلى /downloads/muhaddith (يتخطّى ما رُفع بالحجم نفسه)،
ثم إنشاء رابط مشاركة لكل ملف ليس له رابط، ثم كتابة manifest.json بعناوين الأجزاء الكاملة
(http://…/fsdownload/<معرّف>/<اسم>) ورفعه، وطباعة رابط مشاركة البيان الذي يستعمله التطبيق.

ثبت بالتجربة: مشاركة المجلد لا تصلح للتنزيل الآلي، ومشاركة الملف تعمل مع Range بعد فتح صفحتها (كعكة).
المتغيّر NAS_CONNECT (مثل 127.0.0.1:5000) يوجّه الاتصال إلى ناقل محلي مع إبقاء ترويسة Host الأصلية.
"""
import argparse, hashlib, json, os, re, sys, time, urllib.parse, urllib.request, uuid

# البوابة المفضّلة: HTTPS بشهادة موثوقة وبلا حدّ للحجم؛ البديل: DSM مباشرة على المنفذ ٥٠٠٠ (NAS_SCHEME=http NAS_HOST=nas.fitrahmedia.nl:5000)
HOST = os.environ.get("NAS_HOST", "files.murabbie.org")
SCHEME = os.environ.get("NAS_SCHEME", "https")
USER, PASS = os.environ.get("NAS_USER", "manus"), os.environ.get("NAS_PASS", "")  # سرّي: export NAS_PASS='…'
REMOTE_DIR = "/downloads/muhaddith"
CONNECT = os.environ.get("NAS_CONNECT", HOST)
BASE = f"{SCHEME}://{CONNECT}/webapi"
# العناوين العامة في البيان عبر HTTPS على النطاق الفرعي للتطبيق (نفس روابط المشاركة)؛ NAS_PUBLIC يغيّرها
PUBLIC = os.environ.get("NAS_PUBLIC", "https://muhaddith.murabbie.org")

AR = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")
def ar(x): return str(x).translate(AR)

def call(cgi, params, data=None, headers=None, timeout=1800):
    url = f"{BASE}/{cgi}?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET", headers={"Host": HOST, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read().decode())

def login():
    j = call("auth.cgi", dict(api="SYNO.API.Auth", version=3, method="login", account=USER, passwd=PASS, session="FileStation", format="sid"))
    if not j.get("success"): raise SystemExit(f"فشل تسجيل الدخول: {j}")
    return j["data"]["sid"]

def listing(sid):
    j = call("entry.cgi", dict(api="SYNO.FileStation.List", version=2, method="list", folder_path=REMOTE_DIR, additional='["size"]', limit=500, _sid=sid))
    return {f["name"]: f["additional"]["size"] for f in j.get("data", {}).get("files", [])} if j.get("success") else {}

def remote_md5(sid, remote_path, timeout=600):
    """بصمة MD5 لملف على NAS عبر SYNO.FileStation.MD5 (مهمة غير متزامنة)"""
    j = call("entry.cgi", dict(api="SYNO.FileStation.MD5", version=2, method="start", file_path=remote_path, _sid=sid))
    if not j.get("success"): return None
    tid = j["data"]["taskid"]; t0 = time.time()
    while time.time() - t0 < timeout:
        j = call("entry.cgi", dict(api="SYNO.FileStation.MD5", version=2, method="status", taskid=json.dumps(tid), _sid=sid))
        if j.get("success") and j["data"].get("finished"):
            call("entry.cgi", dict(api="SYNO.FileStation.MD5", version=2, method="stop", taskid=json.dumps(tid), _sid=sid))
            return j["data"].get("md5")
        time.sleep(1)
    return None

def local_md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def upload(sid, path, name=None):
    name = name or os.path.basename(path); b = "----muhaddith" + uuid.uuid4().hex
    head = b"".join(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in (("path", REMOTE_DIR), ("create_parents", "true"), ("overwrite", "true")))
    head += f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
    body = head + open(path, "rb").read() + f"\r\n--{b}--\r\n".encode()
    j = call("entry.cgi", dict(api="SYNO.FileStation.Upload", version=2, method="upload", _sid=sid), data=body, headers={"Content-Type": f"multipart/form-data; boundary={b}"})
    if not j.get("success"): raise RuntimeError(f"فشل رفع {name}: {j}")

def links(sid):
    j = call("entry.cgi", dict(api="SYNO.FileStation.Sharing", version=3, method="list", limit=500, _sid=sid))
    return {l["path"]: l["id"] for l in j.get("data", {}).get("links", []) if l.get("status") == "valid"}

def make_links(sid, paths):
    if not paths: return
    j = call("entry.cgi", dict(api="SYNO.FileStation.Sharing", version=3, method="create", path=json.dumps(paths), _sid=sid))
    if not j.get("success"): raise RuntimeError(f"فشل إنشاء روابط المشاركة: {j}")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--dir", required=True); ap.add_argument("--manifest"); ap.add_argument("--only-links", action="store_true")
    ap.add_argument("--notes", default="", help="ما الجديد في هذا الإصدار (يظهر للمستخدم في بطاقة التحديث)")
    a = ap.parse_args()
    files = sorted(f for f in os.listdir(a.dir) if (re.match(r"muhaddith_\w+\.(db|bin)\.gz\.\d{3}$", f) or re.match(r"muhaddith-[\d.]+-(arm64|arm32)\.apk$", f) or f == "SHA256SUMS.txt") and os.path.isfile(os.path.join(a.dir, f)))
    sid = login(); have = listing(sid)
    if not a.only_links:
        for i, n in enumerate(files, 1):
            p = os.path.join(a.dir, n); s = os.path.getsize(p)
            if have.get(n) == s:
                # الحجم نفسه لا يكفي: أجزاء الحزم كلها ٢٩ م.ب بالضبط، فنقارن البصمة ما لم يُطلب --no-verify
                if remote_md5(sid, f"{REMOTE_DIR}/{n}") == local_md5(p): print(f"[{ar(i)}/{ar(len(files))}] {n} موجود — تخطٍّ"); continue
                print(f"[{ar(i)}/{ar(len(files))}] {n} الحجم نفسه والمحتوى مختلف — يُعاد رفعه")
            print(f"[{ar(i)}/{ar(len(files))}] {n} ({ar(f'{s/1048576:.1f}')} م.ب) …", end="", flush=True); t0 = time.time(); upload(sid, p); print(f" {ar(int(time.time()-t0))} ث")
        have = listing(sid)
    lk = links(sid)
    need = [f"{REMOTE_DIR}/{n}" for n in files if f"{REMOTE_DIR}/{n}" not in lk]
    make_links(sid, need); lk = links(sid)
    print(f"روابط المشاركة: {ar(len([p for p in lk if p.startswith(REMOTE_DIR + '/')]))} ملفًا (أُنشئ {ar(len(need))})")
    # البيان بعناوين كاملة
    mpath = a.manifest or os.path.join(a.dir, "manifest.json")
    m = json.load(open(mpath, encoding="utf-8"))
    missing = []
    for pk in m["packs"]:
        names = pk.get("parts") or [pk["file"]]
        urls = []
        for n in names:
            n = n.rsplit("/", 1)[-1]
            lid = lk.get(f"{REMOTE_DIR}/{n}")
            if not lid: missing.append(n); continue
            urls.append(f"{PUBLIC}/fsdownload/{lid}/{n}")
        pk["parts"] = urls
    m["source"] = "nas"; m["folder"] = f"{PUBLIC}/sharing/ehniFEwJg"
    # كتلة app: إصدار التطبيق وروابط APK لكل معالج (للتحديث من داخل التطبيق)
    gradle = next((g for g in [os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build.gradle.kts"), os.path.join(os.path.dirname(os.path.abspath(__file__)), "build.gradle.kts")] if os.path.exists(g)), "")
    apks = {}; shas = {}
    for n in files:
        mm = re.match(r"muhaddith-([\d.]+)-(arm64|arm32)\.apk$", n)
        if mm and f"{REMOTE_DIR}/{n}" in lk:
            apks[mm.group(2)] = f"{PUBLIC}/fsdownload/{lk[f'{REMOTE_DIR}/{n}']}/{n}"
            h = hashlib.sha256(open(os.path.join(a.dir, n), "rb").read()).hexdigest(); shas[mm.group(2)] = h
    if apks and os.path.exists(gradle):
        g = open(gradle, encoding="utf-8").read()
        vc = int(re.search(r"versionCode\s*=\s*(\d+)", g).group(1)); vn = re.search(r'versionName\s*=\s*"([^"]+)"', g).group(1)
        notes = a.notes or (open(os.path.join(a.dir, "RELEASE_NOTES.txt"), encoding="utf-8").read().strip() if os.path.exists(os.path.join(a.dir, "RELEASE_NOTES.txt")) else "")
        m["app"] = {"version_code": vc, "version_name": vn, "notes": notes, "apk": apks, "sha256": shas}
        print(f"إصدار التطبيق في البيان: {vn} ({ar(vc)}) — {', '.join(apks)}")
    if missing:
        print("تنبيه: ملفات في البيان ليست على NAS:", ", ".join(missing))
        if any(not pk["parts"] for pk in m["packs"]):
            raise SystemExit("توقّف: حزمة بلا أجزاء على الخادم — ارفع أجزاءها أولًا (لا يُكتب بيان ناقص كي لا يتعطّل التطبيق)")
    out = os.path.join(a.dir, "manifest.nas.json")
    json.dump(m, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    upload(sid, out, name="manifest.json")
    make_links(sid, [] if f"{REMOTE_DIR}/manifest.json" in lk else [f"{REMOTE_DIR}/manifest.json"]); lk = links(sid)
    mid = lk[f"{REMOTE_DIR}/manifest.json"]
    print(f"بيان الحزم للتطبيق: {PUBLIC}/sharing/{mid}/  ← هذا هو NAS_BASE_URL في DataPackManager.kt")
    print(f"مجلد المشاركة للبشر: {PUBLIC}/sharing/ehniFEwJg")
    call("auth.cgi", dict(api="SYNO.API.Auth", version=3, method="logout", session="FileStation", _sid=sid))

if __name__ == "__main__":
    main()

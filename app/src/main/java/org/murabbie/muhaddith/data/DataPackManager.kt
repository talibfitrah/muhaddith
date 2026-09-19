package org.murabbie.muhaddith.data

import android.content.Context
import android.net.Uri
import io.requery.android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/** حزمة بيانات معلنة في manifest.json على الخادم */
data class RemotePack(
    val id: String, val name: String, val description: String, val file: String,
    val sizeGz: Long, val sizeDb: Long, val sha256: String?, val hadiths: Int, val books: Int,
    /** أسماء الأجزاء إن كانت الحزمة مجزّأة على الخادم (تُجمع بالترتيب)؛ فارغة = ملف واحد */
    val parts: List<String> = emptyList(),
    /** تاريخ بناء الحزمة على الخادم (للأحكام والشروح) */
    val built: String = "",
    /** إصدار مخطط الحزمة على الخادم (للأحكام والشروح) */
    val schema: String = ""
)

/** إصدار التطبيق المعلن في manifest.json (كتلة app) */
data class RemoteApp(val versionCode: Int, val versionName: String, val notes: String, val apks: Map<String, String>, val sha256: Map<String, String>) {
    /** عنوان APK المناسب لمعالج هذا الجهاز */
    fun urlFor(abis: List<String>): Pair<String, String>? {
        val key = if (abis.any { it == "arm64-v8a" }) "arm64" else "arm32"
        val u = apks[key] ?: apks.values.firstOrNull() ?: return null
        return key to u
    }
}

/**
 * تنزيل حزم البيانات من الخادم (مع استئناف وتحقّق sha256) أو استيرادها من ملفات على الجهاز، ثم تفعيلها.
 * الحزمة: قاعدة SQLite بمخطط المحدِّث المدمج (schema_version=2) مضغوطة gzip (أو غير مضغوطة).
 */
class DataPackManager(private val ctx: Context, private val db: MuhaddithDatabase) {

    companion object {
        /**
         * بيان الحزم على NAS المؤلّف: رابط مشاركة ملف manifest.json نفسه (المجلد /downloads/muhaddith).
         * كل جزء في هذا البيان يحمل عنوانه الكامل http://…/fsdownload/<معرّف مشاركة الجزء>/<اسمه>.
         * مشاركة المجلد للبشر: http://nas.fitrahmedia.nl:5000/sharing/ehniFEwJg
         */
        const val NAS_BASE_URL = "http://nas.fitrahmedia.nl:5000/sharing/4ojRClfLk/"
        /** الأماكن التي يبحث فيها التطبيق عن manifest.json بالترتيب؛ أول مكان يستجيب يُعتمد */
        val DEFAULT_BASE_URLS = listOf(
            NAS_BASE_URL,
            // العناوين القديمة (قبل الانتقال إلى NAS) — تُعدّ قديمة فتُستبدل بـ NAS تلقائيًّا
            "https://pub-12c9ffe787814026b68d86b06d8d336a.r2.dev/android/",
            "http://nas.fitrahmedia.nl:5000/sharing/DANcUXhZ2/"
        )
        val DEFAULT_BASE_URL get() = DEFAULT_BASE_URLS.first()
        /** عناوين كانت افتراضية في إصدارات سابقة؛ إن كانت هي المحفوظة فليست اختيار المستخدم، فيُقدَّم NAS عليها */
        private val LEGACY_DEFAULTS = DEFAULT_BASE_URLS.drop(1)
        const val SETTING_BASE_URL = "packs_base_url"
        private const val SETTING_MIGRATED = "packs_base_url_v8"
        private const val PART_SUFFIX = ".gz.part"
    }

    init {
        // ترقية من إصدار سابق: العنوان المحفوظ تلقائيًّا (لا بيد المستخدم) يُستبدل بمجلد NAS مرة واحدة
        if (db.setting(SETTING_MIGRATED) == null) {
            val cur = db.setting(SETTING_BASE_URL)
            if (cur != null && cur.let { if (it.endsWith("/")) it else "$it/" } in LEGACY_DEFAULTS) db.putSetting(SETTING_BASE_URL, DEFAULT_BASE_URL)
            db.putSetting(SETTING_MIGRATED, "1")
        }
    }

    val baseUrl: String get() = (db.setting(SETTING_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL).let { if (it.endsWith("/")) it else "$it/" }
    fun setBaseUrl(u: String) = db.putSetting(SETTING_BASE_URL, u.trim())
    /** هل المصدر الحالي مشاركة NAS سينولوجي؟ */
    val isNasSource: Boolean get() = PackSource.parseSyno(baseUrl) != null

    fun hasInstalled(): Boolean = MuhaddithDatabase.fullFile(ctx).let { it.exists() && it.length() > 0 }

    // ---------- البيان ----------

    /** آخر كتلة app قُرئت من البيان (إصدار التطبيق على الخادم) */
    @Volatile var remoteApp: RemoteApp? = null
        private set
    /** آخر قائمة حزم قُرئت من البيان */
    @Volatile var lastManifest: List<RemotePack> = emptyList()
        private set

    /** يجرّب العنوان المحفوظ ثم العناوين الافتراضية؛ ويثبّت أول عنوان يستجيب */
    fun fetchManifest(): List<RemotePack> {
        val candidates = (listOf(baseUrl) + DEFAULT_BASE_URLS).distinct()
        var lastError: Exception? = null
        for (base in candidates) {
            try {
                val packs = fetchManifestFrom(base)
                if (base != baseUrl) setBaseUrl(base)
                lastManifest = packs
                return packs
            } catch (e: Exception) { lastError = e }
        }
        throw lastError ?: IllegalStateException("تعذّر جلب بيان الحزم")
    }

    private fun fetchManifestFrom(base: String): List<RemotePack> {
        val conn = PackSource(base).connect("manifest.json", binary = false)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("تعذّر جلب بيان الحزم (HTTP ${conn.responseCode})")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalStateException("الملف manifest.json على الخادم ليس بيان حزم") }
            val arr = root.optJSONArray("packs") ?: throw IllegalStateException("الملف manifest.json على الخادم ليس بيان حزم")
            remoteApp = root.optJSONObject("app")?.let { a ->
                val apks = HashMap<String, String>(); val shas = HashMap<String, String>()
                a.optJSONObject("apk")?.let { o -> o.keys().forEach { k -> apks[k] = o.getString(k) } }
                a.optJSONObject("sha256")?.let { o -> o.keys().forEach { k -> shas[k] = o.getString(k) } }
                RemoteApp(a.optInt("version_code", 0), a.optString("version_name", ""), a.optString("notes", ""), apks, shas)
            }
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val partsArr = o.optJSONArray("parts")
                RemotePack(
                    id = o.getString("id"), name = o.getString("name"),
                    description = o.optString("description", ""), file = o.getString("file"),
                    sizeGz = o.optLong("size_gz", -1), sizeDb = o.optLong("size_db", -1),
                    sha256 = o.optString("sha256_gz", null)?.takeIf { it.isNotBlank() },
                    hadiths = o.optInt("hadiths", 0), books = o.optInt("books", 0),
                    parts = if (partsArr == null) emptyList() else (0 until partsArr.length()).map { partsArr.getString(it) },
                    built = o.optString("built", ""), schema = o.optString("schema", "")
                )
            }
        } finally { conn.disconnect() }
    }

    // ---------- التنزيل مع الاستئناف ----------

    private val fetcher get() = PackFetcher(MuhaddithDatabase.storageDir(ctx), baseUrl) { PackWorker.gate(ctx) }

    /**
     * ينزّل الحزمة (ملفًا واحدًا أو أجزاءً) مع الاستئناف، يتحقّق من sha256، يفكّ الضغط، ثم يفعّل.
     * onProgress(bytesDone, total, phase): phase = 0 تنزيل، 1 تحقّق، 2 فكّ ضغط.
     */
    fun download(pack: RemotePack, onProgress: (Long, Long, Int) -> Unit) {
        log("بدء تنزيل «${pack.name}» (${pack.sizeGz / 1_000_000} م.ب)")
        val f = fetcher
        val files = f.fetch(pack, onProgress)
        val streams = files.map { FileInputStream(it) }
        java.io.SequenceInputStream(java.util.Collections.enumeration(streams)).use { input ->
            install(input, files.sumOf { it.length() }) { d, t -> onProgress(d, t, 2) }
        }
        files.forEach { f.discard(it) }
        pack.sha256?.let { db.putSetting("pack_sha_" + pack.id, it) }
        log("تم تثبيت «${pack.name}»")
    }

    /** بصمة الحزمة كما ثُبّتت آخر مرة (للمقارنة مع الخادم)؛ null إن ثُبّتت قبل هذا الإصدار */
    fun installedSha(id: String): String? = db.setting("pack_sha_$id")

    // ---------- سجلّ العمليات (آخر ٤٠ سطرًا؛ يظهر للمستخدم ليرسله عند الخلل) ----------
    fun log(msg: String) {
        try {
            val cur = db.setting("pack_log")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            db.putSetting("pack_log", (cur + "$ts  $msg").takeLast(40).joinToString("\n"))
        } catch (_: Exception) { }
    }
    fun logLines(): List<String> = db.setting("pack_log")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    fun clearLog() = db.putSetting("pack_log", "")

    fun deletePartial() = fetcher.discardAll()

    /** ينزّل APK التحديث إلى الذاكرة المؤقتة (يُشارك عبر FileProvider) ويتحقّق من بصمته إن أُعلنت؛ يعيد الملف */
    fun downloadUpdate(app: RemoteApp, onProgress: (Long, Long, Int) -> Unit): File {
        val (key, url) = app.urlFor(android.os.Build.SUPPORTED_ABIS.toList()) ?: throw IllegalStateException("لا ملف تحديث لهذا الجهاز")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = PackFetcher(dir, baseUrl) { PackWorker.gate(ctx) }
        val pack = RemotePack("app", "التحديث ${app.versionName}", "", "muhaddith-${app.versionName}-$key.apk", -1, -1, app.sha256[key], 0, 0, listOf(url))
        val files = f.fetch(pack, onProgress)
        val out = File(dir, "muhaddith-${app.versionName}-$key.apk")
        files.first().renameTo(out); File(files.first().path + PackFetcher.SIZE_SUFFIX).delete()
        if (out.length() < 1_000_000) { out.delete(); throw IllegalStateException("ملف التحديث ناقص") }
        return out
    }

    fun partialBytes(): Long = fetcher.partialBytes()

    // ---------- الاستيراد من ملفات ----------

    /**
     * استيراد من ملف واحد أو عدة أجزاء اختارها المستخدم (SAF) — لا يحتاج أي صلاحية.
     * الأجزاء تُرتَّب باسمها (…gz.001, …gz.002) وتُقرأ متتابعةً كأنها ملف واحد.
     */
    fun importFrom(uris: List<Uri>, onProgress: (Long, Long, Int) -> Unit) {
        if (uris.isEmpty()) throw IllegalStateException("لم يُختر ملف")
        val named = uris.map { it to displayName(it) }
        // إن كانت الملفات أجزاء حزمة معروفة: تحقّق من اكتمالها وترتيبها وبصمتها
        val known = KnownPacks.groupUris(named)
        if (known.isNotEmpty()) {
            val incomplete = known.filter { !it.complete }
            if (known.none { it.complete }) {
                val fp = incomplete.first()
                throw IllegalStateException("أجزاء «${fp.pack.name}» ناقصة: ${fp.missing.joinToString("، ") { "%03d".format(it) }} — نزّلها ثم أعد المحاولة")
            }
            // تُثبَّت كل الحزم المكتملة بالترتيب: المتون ثم النموذج ثم المتجهات
            for (fp in known.filter { it.complete }.sortedBy { listOf("corpus", "ahkam", "shuruh", "model", "vectors").indexOf(it.pack.kind) }) importFound(fp, onProgress)
            if (incomplete.isNotEmpty()) throw IllegalStateException(
                "ثُبّت ما اكتمل، لكن «${incomplete.first().pack.name}» ناقصة: " + incomplete.first().missing.joinToString("، ") { "%03d".format(it) })
            return
        }
        // وإلا: ملف واحد أو أجزاء غير معروفة تُرتَّب باسمها
        val ordered = named.sortedBy { it.second }.map { it.first }
        installFromUris(ordered, null, onProgress)
    }

    /** استيراد حزمة معروفة وُجدت أجزاؤها كاملة على الهاتف */
    fun importFound(fp: FoundPack, onProgress: (Long, Long, Int) -> Unit) {
        installFromUris(fp.orderedUris(), fp.pack, onProgress)
        db.putSetting("pack_sha_" + fp.pack.id, fp.pack.sha256)
        log("تم تثبيت «${fp.pack.name}» من ملفات الهاتف")
    }

    private fun installFromUris(uris: List<Uri>, pack: KnownPack?, onProgress: (Long, Long, Int) -> Unit) {
        var total = 0L
        for (u in uris) {
            val sz = try { ctx.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
            if (sz < 0) { total = -1; break }
            total += sz
        }
        // الحجم والبصمة في KnownPacks هما لآخر إصدار عرفه التطبيق؛ إن اختلفا فقد تكون الحزمة أحدث من التطبيق، فنكتفي بالتحذير
        // ونعتمد على فحص سلامة gzip ومخطط sqlite عند التثبيت (الحزمة التالفة أو الناقصة تفشل هناك برسالة واضحة)
        if (pack != null && total > 0 && total != pack.sizeGz) log("تنبيه: حجم أجزاء «${pack.name}» ${total / 1_000_000} م.ب يخالف ما يعرفه التطبيق ${pack.sizeGz / 1_000_000} م.ب — ربما إصدار أحدث؛ سيُتحقَّق عند التثبيت")
        if (pack != null && total > 0 && total == pack.sizeGz) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = 0L
            val buf = ByteArray(1 shl 16)
            for (u in uris) (ctx.contentResolver.openInputStream(u) ?: throw IllegalStateException("تعذّر فتح الملف")).use { input ->
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    md.update(buf, 0, n); done += n
                    if (done - last > (8L shl 20)) { last = done; onProgress(done, total, 1) }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(pack.sha256, ignoreCase = true)) throw IllegalStateException(
                "الأجزاء لا تطابق بصمة «${pack.name}» — أحد الملفات تالف أو ناقص التنزيل؛ أعد تنزيله")
        }
        val streams = uris.map { ctx.contentResolver.openInputStream(it) ?: throw IllegalStateException("تعذّر فتح الملف") }
        java.io.SequenceInputStream(java.util.Collections.enumeration(streams)).use { install(it, total) { d, t -> onProgress(d, t, 2) } }
    }

    fun scanFolder(tree: Uri): List<FoundPack> = KnownPacks.scanTree(ctx, tree)

    fun scanPublicFolders(): List<FoundPack> = KnownPacks.scanPublicFolders()

    /** هل يملك التطبيق الوصول المباشر إلى مجلدات الهاتف؟ */
    fun hasAllFilesAccess(): Boolean =
        if (!org.murabbie.muhaddith.BuildConfig.ALL_FILES_ACCESS) false
        else if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun displayNames(uris: List<Uri>): List<Pair<Uri, String>> = uris.map { it to displayName(it) }

    private fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: uri.toString()
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: uri.toString()
    }

    // ---------- التثبيت المشترك ----------

    private fun install(raw: InputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val tmp = File(MuhaddithDatabase.storageDir(ctx), MuhaddithDatabase.FULL_TMP)
        if (tmp.exists()) tmp.delete()
        val counting = CountingStream(raw) { done -> onProgress(done, total) }
        val pb = PushbackInputStream(counting, 2)
        val b1 = pb.read(); val b2 = pb.read()
        if (b1 < 0) throw IllegalStateException("الملف فارغ")
        pb.unread(b2); pb.unread(b1)
        val isGzip = b1 == 0x1f && b2 == 0x8b
        val stream: InputStream = if (isGzip) GZIPInputStream(pb, 1 shl 16) else pb
        FileOutputStream(tmp).use { out -> stream.copyTo(out, 1 shl 18) }

        // ما نوع الملف بعد فكّ الضغط؟
        val head = ByteArray(16); java.io.FileInputStream(tmp).use { it.read(head) }
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        if (magic == "MSBP") { installModelContainer(tmp); return }
        if (magic == "MSBV") { installVectors(tmp, head); return }

        // قاعدة sqlite: حزمة الشروح، أو حزمة الأحكام ومختلف الحديث، أو حزمة المتون
        val ds = MuhaddithDatabase.sqliteDataset(tmp)
        if (ds == "shuruh") {
            val okS = try { SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { MuhaddithDatabase.isValidShuruh(it) } } catch (_: Exception) { false }
            if (!okS) { tmp.delete(); throw IllegalStateException("حزمة الشروح تالفة") }
            val t = MuhaddithDatabase.shuruhFile(ctx)
            if (t.exists()) t.delete()
            if (!tmp.renameTo(t)) throw IllegalStateException("تعذّر حفظ حزمة الشروح")
            db.reopenShuruh()
            return
        }
        if (ds == "ahkam") {
            val okA = try { SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { MuhaddithDatabase.isValidAhkam(it) } } catch (_: Exception) { false }
            if (!okA) { tmp.delete(); throw IllegalStateException("حزمة الأحكام تالفة") }
            val t = MuhaddithDatabase.ahkamFile(ctx)
            if (t.exists()) t.delete()
            if (!tmp.renameTo(t)) throw IllegalStateException("تعذّر حفظ حزمة الأحكام")
            db.reopenAhkam()
            return
        }
        val ok = try {
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { MuhaddithDatabase.isValidCorpus(it) }
        } catch (_: Exception) { false }
        if (!ok) { tmp.delete(); throw IllegalStateException("الملف ليس حزمة المحدِّث صالحة") }

        val target = MuhaddithDatabase.fullFile(ctx)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("تعذّر حفظ الحزمة")
        db.reopenCorpus()
    }

    /** نقل الحزمة المثبَّتة بين الذاكرة الداخلية وبطاقة الذاكرة */
    fun moveStorage(to: String, onProgress: (Long, Long, Int) -> Unit) {
        val current = MuhaddithDatabase.fullFile(ctx)
        val targetDir = if (to == "sd") (MuhaddithDatabase.sdDir(ctx) ?: throw IllegalStateException("لا توجد بطاقة ذاكرة")) else ctx.filesDir
        if (!current.exists()) { MuhaddithDatabase.setStorageChoice(ctx, to); db.reopenCorpus(); return }
        val target = File(targetDir, MuhaddithDatabase.FULL_FILE)
        if (target.canonicalPath == current.canonicalPath) return
        val free = targetDir.usableSpace
        if (free < current.length() + (64L shl 20)) throw IllegalStateException("المساحة غير كافية في الوجهة (${free / 1_000_000} م.ب متاحة، يلزم ${current.length() / 1_000_000} م.ب)")
        val tmp = File(targetDir, MuhaddithDatabase.FULL_TMP)
        val total = current.length(); var done = 0L; var last = 0L
        current.inputStream().use { input -> FileOutputStream(tmp).use { out ->
            val buf = ByteArray(1 shl 18)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                if (done - last > (4L shl 20)) { last = done; onProgress(done, total, 3); PackWorker.gate(ctx)?.let { throw PackFetcher.Stopped(it) } } }
        } }
        if (tmp.length() != total) { tmp.delete(); throw IllegalStateException("فشل النقل") }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        MuhaddithDatabase.setStorageChoice(ctx, to)
        db.reopenCorpus()
        current.delete()
        // حزمتا الأحكام والشروح إن وُجدتا تنتقلان معها
        for (name in listOf(MuhaddithDatabase.AHKAM_FILE, MuhaddithDatabase.SHURUH_FILE)) {
            val ah = File(current.parentFile, name)
            if (ah.exists()) {
                val at = File(targetDir, name)
                try { ah.copyTo(at, overwrite = true); ah.delete() } catch (_: Exception) { }
            }
        }
        db.reopenAhkam(); db.reopenShuruh()
    }

    // ---------- حزم البحث الدلالي ----------

    val semanticDir: File get() = File(ctx.filesDir, "semantic").apply { mkdirs() }

    /** حاوية MSBP: [count u32][(name u16+bytes, size u64, bytes)…] */
    private fun installModelContainer(tmp: File) {
        java.io.DataInputStream(java.io.FileInputStream(tmp).buffered(1 shl 16)).use { d ->
            d.skipBytes(4)
            val count = Integer.reverseBytes(d.readInt())
            for (i in 0 until count) {
                val nlen = java.lang.Short.reverseBytes(d.readShort()).toInt() and 0xFFFF
                val nb = ByteArray(nlen); d.readFully(nb)
                val name = String(nb, Charsets.UTF_8).substringAfterLast('/')
                val size = java.lang.Long.reverseBytes(d.readLong())
                val out = File(semanticDir, "$name.part")
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(1 shl 16); var left = size
                    while (left > 0) { val n = d.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IllegalStateException("حاوية النموذج ناقصة"); o.write(buf, 0, n); left -= n }
                }
                val target = File(semanticDir, name); if (target.exists()) target.delete(); out.renameTo(target)
            }
        }
        tmp.delete()
    }

    private fun installVectors(tmp: File, head: ByteArray) {
        val version = java.nio.ByteBuffer.wrap(head, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        var dataset = "full"
        if (version >= 2) {
            val tag = ByteArray(16); java.io.RandomAccessFile(tmp, "r").use { it.seek(16); it.readFully(tag) }
            dataset = String(tag, Charsets.UTF_8).trim(' ', '\u0000')
        }
        val target = org.murabbie.muhaddith.semantic.SemanticEngine.vectorsFile(semanticDir, dataset)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    fun removeSemantic() { semanticDir.listFiles()?.forEach { it.delete() } }

    fun semanticBytes(): Long = semanticDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun removeInstalled() {
        val f = MuhaddithDatabase.fullFile(ctx)
        if (f.exists()) f.delete()
        db.reopenCorpus()
    }

    fun hasAhkam(): Boolean = db.ahkam != null

    fun removeShuruh() {
        val f = MuhaddithDatabase.shuruhFile(ctx)
        if (f.exists()) f.delete()
        db.reopenShuruh()
    }

    fun removeAhkam() {
        val f = MuhaddithDatabase.ahkamFile(ctx)
        if (f.exists()) f.delete()
        db.reopenAhkam()
    }

    fun freeSpaceBytes(): Long = ctx.filesDir.usableSpace

    private class CountingStream(private val inner: InputStream, private val cb: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        private fun bump(n: Int) {
            if (n > 0) {
                count += n
                if (count - lastReport > (1L shl 20)) { lastReport = count; cb(count) }
            }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { bump(it) }
        override fun close() = inner.close()
    }
}

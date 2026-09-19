package org.murabbie.muhaddith.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * يدير قاعدتين: قاعدة المتون (قراءة فقط) وقاعدة المستخدم (كتابة).
 *
 * قاعدة المتون المشحونة داخل الحزمة تحوي الكتب التسعة، وإذا وُجدت الحزمة الكاملة
 * (١٤٠٠ كتاب) منزَّلة أو مستوردة في مجلد التطبيق فهي المقدَّمة.
 */
class MuhaddithDatabase private constructor(
    @Volatile var corpus: SQLiteDatabase,
    val userDb: SQLiteDatabase,
    private val ctx: Context
) {
    companion object {
        const val ASSET_NAME = "muhaddith_corpus.db"
        const val BUNDLED_FILE = "muhaddith_corpus_bundled.db"
        const val FULL_FILE = "muhaddith_corpus_full.db"
        const val FULL_TMP = "muhaddith_corpus_full.db.part"
        const val AHKAM_FILE = "muhaddith_ahkam.db"
        const val SHURUH_FILE = "muhaddith_shuruh.db"
        private const val USER_FILE = "muhaddith_user.db"

        @Volatile private var instance: MuhaddithDatabase? = null

        fun get(context: Context): MuhaddithDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** مكان تخزين الحزمة: internal (افتراضي) أو sd (بطاقة ذاكرة خارجية إن وُجدت) */
        fun storageChoice(ctx: Context): String = ctx.getSharedPreferences("muhaddith_storage", Context.MODE_PRIVATE).getString("storage", "internal") ?: "internal"
        fun setStorageChoice(ctx: Context, v: String) { ctx.getSharedPreferences("muhaddith_storage", Context.MODE_PRIVATE).edit().putString("storage", v).apply() }

        /** مجلد بطاقة الذاكرة الخارجية القابلة للإزالة إن وُجدت */
        fun sdDir(ctx: Context): File? = try {
            ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull {
                android.os.Environment.isExternalStorageRemovable(it) && android.os.Environment.getExternalStorageState(it) == android.os.Environment.MEDIA_MOUNTED
            }
        } catch (_: Exception) { null }

        fun storageDir(ctx: Context): File {
            if (storageChoice(ctx) == "sd") sdDir(ctx)?.let { return it }
            return ctx.filesDir
        }

        fun fullFile(ctx: Context) = File(storageDir(ctx), FULL_FILE)

        /** حزمة الأحكام ومختلف الحديث (اختيارية، تُفتح إلى جانب المتون) */
        fun ahkamFile(ctx: Context) = File(storageDir(ctx), AHKAM_FILE)
        fun shuruhFile(ctx: Context) = File(storageDir(ctx), SHURUH_FILE)

        fun otherAhkamFile(ctx: Context): File? {
            val alt = if (storageChoice(ctx) == "sd") ctx.filesDir else sdDir(ctx)
            return alt?.let { File(it, AHKAM_FILE) }?.takeIf { it.exists() }
        }

        /** الحزمة في المكان الآخر (إن كانت هناك نسخة قديمة بعد تغيير المكان) */
        fun otherFullFile(ctx: Context): File? {
            val alt = if (storageChoice(ctx) == "sd") ctx.filesDir else sdDir(ctx)
            return alt?.let { File(it, FULL_FILE) }?.takeIf { it.exists() }
        }

        private fun build(ctx: Context): MuhaddithDatabase {
            val corpus = openCorpus(ctx)
            val user = SQLiteDatabase.openOrCreateDatabase(
                File(ctx.filesDir, USER_FILE).absolutePath, null
            )
            createUserTables(user)
            return MuhaddithDatabase(corpus, user, ctx).also { it.ahkam = openAhkam(ctx); it.shuruh = openShuruh(ctx) }
        }

        private fun openAhkam(ctx: Context): SQLiteDatabase? {
            val f = ahkamFile(ctx)
            if (!f.exists() || f.length() == 0L) return null
            return try {
                val db = openReadOnly(f)
                if (isValidAhkam(db)) db else { db.close(); null }
            } catch (_: Exception) { null }
        }

        const val AHKAM_SCHEMA = "2"

        fun isValidAhkam(db: SQLiteDatabase): Boolean = try {
            db.rawQuery("SELECT value FROM meta WHERE key='dataset'", null).use { it.moveToFirst() && it.getString(0) == "ahkam" } &&
                db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use { it.moveToFirst() && it.getString(0) == AHKAM_SCHEMA } &&
                db.rawQuery("SELECT conf FROM rulings LIMIT 1", null).use { it.moveToFirst() }
        } catch (_: Exception) { false }

        /** حزمة أحكام موجودة لكنها من إصدار أقدم (بلا درجات الثقة) */
        fun isOutdatedAhkam(f: File): Boolean = try {
            f.exists() && f.length() > 0 && openReadOnly(f).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key='dataset'", null).use { it.moveToFirst() && it.getString(0) == "ahkam" } && !isValidAhkam(db)
            }
        } catch (_: Exception) { false }

        private fun openShuruh(ctx: Context): SQLiteDatabase? {
            val f = shuruhFile(ctx)
            if (!f.exists() || f.length() == 0L) return null
            return try { val db = openReadOnly(f); if (isValidShuruh(db)) db else { db.close(); null } } catch (_: Exception) { null }
        }

        const val SHURUH_SCHEMA = "5"
        fun isValidShuruh(db: SQLiteDatabase): Boolean = try {
            db.rawQuery("SELECT value FROM meta WHERE key='dataset'", null).use { it.moveToFirst() && it.getString(0) == "shuruh" } &&
                db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use { it.moveToFirst() && it.getString(0) == SHURUH_SCHEMA } &&
                db.rawQuery("SELECT id, kind, fine_id FROM sh_points LIMIT 1", null).use { it.moveToFirst() } &&
                db.rawQuery("SELECT point_id FROM sh_point_sources LIMIT 1", null).use { true }
        } catch (_: Exception) { false }

        /** حزمة شروح موجودة لكنها من إصدار أقدم (بلا الآثار وعمود kind) */
        fun isOutdatedShuruh(f: File): Boolean = try {
            f.exists() && f.length() > 0 && openReadOnly(f).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key='dataset'", null).use { it.moveToFirst() && it.getString(0) == "shuruh" } && !isValidShuruh(db)
            }
        } catch (_: Exception) { false }

        /** هل هذا الملف قاعدة sqlite من نوع «أحكام»؟ (للتمييز عند التثبيت) */
        fun sqliteDataset(f: File): String? = try {
            openReadOnly(f).use { db -> db.rawQuery("SELECT value FROM meta WHERE key='dataset'", null).use { if (it.moveToFirst()) it.getString(0) else null } }
        } catch (_: Exception) { null }

        private fun ensureBundled(ctx: Context): File {
            val target = File(ctx.filesDir, BUNDLED_FILE)
            // إن تعذّر قراءة الأصل المضمَّن (خلل في الحزمة) فلا يُسقط التطبيق؛ يُترك الملف كما هو أو فارغًا فيعمل بلا بيانات حتى تُنزَّل الحزم
            val assetSize = try { ctx.assets.openFd(ASSET_NAME).use { it.length } } catch (e: Exception) { android.util.Log.e("Muhaddith", "الأصل المضمَّن غير موجود: $ASSET_NAME", e); return target }
            if (!target.exists() || target.length() != assetSize) {
                val tmp = File(ctx.filesDir, "$BUNDLED_FILE.tmp")
                ctx.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 18) }
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
            return target
        }

        private fun openReadOnly(f: File): SQLiteDatabase =
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        private fun openCorpus(ctx: Context): SQLiteDatabase {
            val full = fullFile(ctx)
            if (full.exists() && full.length() > 0) {
                try {
                    val db = openReadOnly(full)
                    if (isValidCorpus(db)) return db
                    db.close()
                } catch (_: Exception) { }
                full.delete()
            }
            return openReadOnly(ensureBundled(ctx))
        }

        fun isValidCorpus(db: SQLiteDatabase): Boolean = try {
            db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use {
                it.moveToFirst() && it.getString(0) == "2"
            } && db.rawQuery("SELECT rowid FROM hadith_fts LIMIT 1", null).use { it.moveToFirst() }
        } catch (_: Exception) { false }

        private fun createUserTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS collections(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS collection_items(" +
                    "collection_id INTEGER NOT NULL, bhid TEXT NOT NULL, note TEXT, " +
                    "added_at INTEGER, PRIMARY KEY(collection_id, bhid))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS saved_searches(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, query TEXT NOT NULL, " +
                    "engines TEXT, scope TEXT, created_at INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS history(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, query TEXT NOT NULL, at INTEGER)"
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        }
    }

    /** قاعدة الأحكام إن كانت مثبَّتة */
    @Volatile var ahkam: SQLiteDatabase? = null

    @Synchronized
    fun reopenAhkam() {
        val old = ahkam
        ahkam = openAhkam(ctx)
        try { old?.close() } catch (_: Exception) { }
    }

    /** قاعدة الشروح وأسباب الورود إن كانت مثبَّتة */
    @Volatile var shuruh: SQLiteDatabase? = null

    @Synchronized
    fun reopenShuruh() {
        val old = shuruh
        shuruh = openShuruh(ctx)
        try { old?.close() } catch (_: Exception) { }
    }

    fun shuruhMeta(key: String): String? = try {
        shuruh?.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key))?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    fun shuruhInfo(): ShuruhInfo {
        val a = shuruh ?: return ShuruhInfo(installed = false, outdated = isOutdatedShuruh(shuruhFile(ctx)))
        fun n(k: String) = shuruhMeta(k)?.toIntOrNull() ?: 0
        return ShuruhInfo(installed = true, books = n("books_count"), sections = n("sections_count"), points = n("points_count"),
            stories = n("stories_count"), clusters = n("clusters_with_points"), scholars = n("scholars_count"), sizeBytes = shuruhFile(ctx).length(), built = shuruhMeta("built"), athar = n("athar_count"))
    }

    fun ahkamMeta(key: String): String? = try {
        ahkam?.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key))?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    fun ahkamInfo(): AhkamInfo {
        val a = ahkam ?: return AhkamInfo(installed = false, outdated = isOutdatedAhkam(ahkamFile(ctx)))
        fun n(k: String) = ahkamMeta(k)?.toIntOrNull() ?: 0
        return AhkamInfo(
            installed = true, rulings = n("rulings_count"), scholars = n("scholars_count"), sections = n("sections_count"),
            books = n("books_count"), hadiths = n("hadiths_with_rulings"), clusters = n("clusters_with_rulings"),
            sizeBytes = ahkamFile(ctx).length(), built = ahkamMeta("built"), albani = n("albani_rulings")
        )
    }

    fun meta(key: String): String? = try {
        corpus.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** يعيد فتح قاعدة المتون بعد تنزيل الحزمة الكاملة أو حذفها */
    @Synchronized
    fun reopenCorpus() {
        val old = corpus
        corpus = openCorpus(ctx)
        try { old.close() } catch (_: Exception) { }
    }

    fun datasetInfo(): DatasetInfo {
        val ds = meta("dataset")
        val imported = fullFile(ctx).let { it.exists() && it.length() > 0 } && ds != "empty"
        val isFull = ds == "full"
        val f = if (imported) fullFile(ctx) else File(ctx.filesDir, BUNDLED_FILE)
        return DatasetInfo(
            name = when (ds) { "full" -> "الحزمة الكاملة (١٤٠٠ كتاب)"; "tisa" -> "الكتب التسعة"; "empty" -> "لا بيانات بعد"; else -> ds ?: "?" },
            hadiths = meta("hadiths_count")?.toIntOrNull() ?: 0,
            books = meta("books_count")?.toIntOrNull() ?: 0,
            rawis = meta("rawis_count")?.toIntOrNull() ?: 0,
            isFull = isFull,
            sizeBytes = f.length(),
            isEmpty = ds == "empty" || (meta("hadiths_count")?.toIntOrNull() ?: 0) == 0,
            isImported = imported,
            location = if (storageChoice(ctx) == "sd" && sdDir(ctx) != null) "بطاقة الذاكرة" else "الذاكرة الداخلية"
        )
    }

    fun setting(key: String): String? =
        userDb.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun putSetting(key: String, value: String) {
        userDb.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf(key, value))
    }
}

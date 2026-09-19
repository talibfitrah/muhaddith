package org.murabbie.muhaddith.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.murabbie.muhaddith.search.SearchEngine
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class Repository(context: Context) {

    private val db = MuhaddithDatabase.get(context)
    val semanticDir: java.io.File = java.io.File(context.filesDir, "semantic").apply { mkdirs() }
    val semantic = org.murabbie.muhaddith.semantic.SemanticEngine(semanticDir)
    private val engine = SearchEngine({ db.corpus }, ::loadHadiths) { q, k ->
        val ds = db.meta("dataset") ?: return@SearchEngine null
        if (!semantic.ready(ds)) null else semantic.search(q, ds, k).map { it.id }
    }

    /** هل البحث الدلالي جاهز للحزمة المثبَّتة الآن؟ */
    fun semanticReady(): Boolean = db.meta("dataset")?.let { semantic.ready(it) } ?: false
    fun semanticModelReady(): Boolean = semantic.modelReady
    fun semanticVectorsReady(): Boolean = db.meta("dataset")?.let { semantic.vectorsReady(it) } ?: false
    fun setSemanticWeight(w: Double) { engine.semanticWeight = w }

    val database: MuhaddithDatabase get() = db

    /** حزمة أحكام العلماء ومختلف الحديث (اختيارية) */
    val ahkam = AhkamRepo(db)
    /** حزمة الشروح وأسباب الورود (اختيارية) */
    val shuruh = ShuruhRepo(db) { hadithByBhid(it) }

    /** أول حديث في عنقود (لفتح الحديث المجمَّع من مقطع شرح) */
    fun firstOfCluster(clusterId: Long): Hadith? =
        db.corpus.rawQuery(HADITH_SELECT + "WHERE h.cluster_id=? ORDER BY b.priority, h.hadith_num LIMIT 1", arrayOf(clusterId.toString())).use { if (it.moveToFirst()) readHadith(it) else null }

    fun datasetInfo(): DatasetInfo = db.datasetInfo()

    // ---------- فك الضغط ----------

    private fun inflate(blob: ByteArray?): String? {
        if (blob == null || blob.isEmpty()) return null
        val inf = Inflater()
        inf.setInput(blob)
        val out = ByteArrayOutputStream(blob.size * 4)
        val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally { inf.end() }
        return out.toString("UTF-8")
    }

    // ---------- البحث ----------

    fun search(
        query: String, engines: Set<Engine>, scope: SearchScope,
        bookFilter: Long?, hokmFilter: Int?, limit: Int = 200, snippet: Int = 160, bookScope: Set<Long> = emptySet()
    ): List<SearchHit> = engine.search(engine.plan(query, engines, scope, bookFilter, hokmFilter).copy(bookScope = bookScope), limit = limit, perEngineLimit = limit * 2, snippetLen = snippet)

    // ---------- الإعدادات ----------

    fun loadSettings(): AppSettings {
        val m = HashMap<String, String>()
        db.userDb.rawQuery("SELECT key, value FROM settings", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: "" }
        return AppSettings.fromMap(m)
    }

    fun saveSettings(s: AppSettings) {
        db.userDb.beginTransaction()
        try {
            s.toMap().forEach { (k, v) -> db.userDb.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf(k, v)) }
            db.userDb.setTransactionSuccessful()
        } finally { db.userDb.endTransaction() }
    }

    fun clearHistory() { db.userDb.delete("history", null, null) }

    /** تصدير المجموعات وخطط البحث إلى JSON */
    fun exportUserData(): String {
        val root = org.json.JSONObject()
        val cols = org.json.JSONArray()
        for (c in collections()) {
            val o = org.json.JSONObject().put("name", c.name)
            val items = org.json.JSONArray()
            db.userDb.rawQuery("SELECT bhid, note, added_at FROM collection_items WHERE collection_id=?", arrayOf(c.id.toString())).use { cur ->
                while (cur.moveToNext()) items.put(org.json.JSONObject().put("bhid", cur.getString(0)).put("note", cur.getString(1) ?: "").put("added_at", cur.getLong(2)))
            }
            cols.put(o.put("items", items))
        }
        val searches = org.json.JSONArray()
        for (ss in savedSearches()) searches.put(org.json.JSONObject().put("name", ss.name).put("query", ss.query)
            .put("engines", ss.engines.joinToString(",") { it.name }).put("scope", ss.scope.name))
        return root.put("version", 1).put("collections", cols).put("saved_searches", searches).toString(2)
    }

    /** استيراد ملف JSON مصدَّر سابقًا (يُدمج مع الموجود) */
    fun importUserData(json: String): Int {
        val root = org.json.JSONObject(json)
        var n = 0
        val cols = root.optJSONArray("collections") ?: org.json.JSONArray()
        for (i in 0 until cols.length()) {
            val o = cols.getJSONObject(i)
            createCollection(o.getString("name"))
            val id = db.userDb.rawQuery("SELECT id FROM collections WHERE name=?", arrayOf(o.getString("name").trim())).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (id < 0) continue
            val items = o.optJSONArray("items") ?: org.json.JSONArray()
            for (j in 0 until items.length()) { addToCollection(id, items.getJSONObject(j).getString("bhid")); n++ }
        }
        val searches = root.optJSONArray("saved_searches") ?: org.json.JSONArray()
        for (i in 0 until searches.length()) {
            val o = searches.getJSONObject(i)
            val eng = o.optString("engines").split(",").mapNotNull { runCatching { Engine.valueOf(it) }.getOrNull() }.toSet()
            val sc = runCatching { SearchScope.valueOf(o.optString("scope")) }.getOrNull() ?: SearchScope.MATN
            saveSearch(o.getString("name"), o.getString("query"), eng.ifEmpty { setOf(Engine.LITERAL) }, sc); n++
        }
        return n
    }

    fun planSummary(query: String, engines: Set<Engine>): SearchPlan =
        engine.plan(query, engines, SearchScope.MATN, null, null)

    fun highlightTerms(plan: SearchPlan): List<String> = engine.highlightTerms(plan)

    // ---------- الأحاديث ----------

    private val HADITH_SELECT =
        "SELECT h.id, h.bhid, h.book_id, b.title, h.hadith_num, h.page_num, h.cluster_id, h.hokm, " +
            "h.block_type, h.chapter, h.sanad, h.matn FROM hadiths h JOIN books b ON b.id = h.book_id "

    private fun readHadith(c: Cursor) = Hadith(
        id = c.getLong(0), bhid = c.getString(1), bookId = c.getLong(2), bookTitle = c.getString(3),
        hadithNum = if (c.isNull(4)) null else c.getInt(4),
        pageNum = if (c.isNull(5)) null else c.getInt(5),
        clusterId = if (c.isNull(6)) null else c.getLong(6),
        hokm = if (c.isNull(7)) null else c.getInt(7),
        blockType = if (c.isNull(8)) null else c.getInt(8),
        chapter = c.getString(9),
        sanad = inflate(if (c.isNull(10)) null else c.getBlob(10)),
        matn = inflate(c.getBlob(11)) ?: ""
    )

    private fun loadHadiths(ids: List<Long>): Map<Long, Hadith> {
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, Hadith>()
        ids.chunked(400).forEach { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            db.corpus.rawQuery(HADITH_SELECT + "WHERE h.id IN ($ph)", chunk.map { it.toString() }.toTypedArray())
                .use { c -> while (c.moveToNext()) readHadith(c).let { out[it.id] = it } }
        }
        return out
    }

    fun hadith(id: Long): Hadith? =
        db.corpus.rawQuery(HADITH_SELECT + "WHERE h.id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) readHadith(it) else null
        }

    fun hadithByBhid(bhid: String): Hadith? =
        db.corpus.rawQuery(HADITH_SELECT + "WHERE h.bhid=?", arrayOf(bhid)).use {
            if (it.moveToFirst()) readHadith(it) else null
        }

    private fun list(sql: String, args: Array<String>): List<Hadith> =
        db.corpus.rawQuery(sql, args).use { c ->
            val out = ArrayList<Hadith>(c.count)
            while (c.moveToNext()) out.add(readHadith(c))
            out
        }

    /** طرق الحديث المجمَّع: الروايات الأخرى بنفس cluster_id */
    fun clusterWays(clusterId: Long?, exclude: Long, limit: Int = 60): List<Hadith> {
        if (clusterId == null) return emptyList()
        return list(
            HADITH_SELECT + "WHERE h.cluster_id=? AND h.id<>? ORDER BY b.priority, h.hadith_num LIMIT ?",
            arrayOf(clusterId.toString(), exclude.toString(), limit.toString())
        )
    }

    fun clusterWayCount(clusterId: Long?): Int {
        if (clusterId == null) return 0
        return db.corpus.rawQuery("SELECT COUNT(*) FROM hadiths WHERE cluster_id=?", arrayOf(clusterId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun cluster(id: Long?): Cluster? {
        if (id == null) return null
        return db.corpus.rawQuery(
            "SELECT id, taraf, hokm_type, sahaba_count, mokararat_count FROM clusters WHERE id=?",
            arrayOf(id.toString())
        ).use {
            if (!it.moveToFirst()) null else Cluster(
                it.getLong(0), it.getString(1),
                if (it.isNull(2)) null else it.getInt(2),
                if (it.isNull(3)) null else it.getInt(3),
                if (it.isNull(4)) null else it.getInt(4)
            )
        }
    }

    fun clusterSahaba(clusterId: Long?): List<ClusterSahabi> {
        if (clusterId == null) return emptyList()
        return db.corpus.rawQuery(
            "SELECT $RAWI_COLS, cs.way_count, cs.taraf FROM cluster_sahaba cs JOIN rawis r ON r.id=cs.rawi_id " +
                "WHERE cs.cluster_id=? ORDER BY cs.way_count DESC LIMIT 80",
            arrayOf(clusterId.toString())
        ).use { c ->
            val out = ArrayList<ClusterSahabi>()
            while (c.moveToNext()) out.add(
                ClusterSahabi(readRawi(c), if (c.isNull(13)) null else c.getInt(13), c.getString(14))
            )
            out
        }
    }

    fun hadithsOfBook(bookId: Long, offset: Int, limit: Int = 50): List<Hadith> =
        list(
            HADITH_SELECT + "WHERE h.book_id=? ORDER BY h.hadith_num LIMIT ? OFFSET ?",
            arrayOf(bookId.toString(), limit.toString(), offset.toString())
        )

    fun hadithsOfRawi(rawiId: Long, offset: Int, limit: Int = 50): List<Hadith> =
        list(
            HADITH_SELECT + "JOIN hadith_rawis hr ON hr.hadith_id=h.id WHERE hr.rawi_id=? " +
                "ORDER BY b.priority, h.hadith_num LIMIT ? OFFSET ?",
            arrayOf(rawiId.toString(), limit.toString(), offset.toString())
        )

    fun rawiHadithCount(rawiId: Long): Int =
        db.corpus.rawQuery("SELECT COUNT(*) FROM hadith_rawis WHERE rawi_id=?", arrayOf(rawiId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- الرواة ----------

    private val RAWI_COLS =
        "r.id, r.name, r.shohra, r.konya, r.lakab, r.rotba, r.wasf_rotba, r.tabaka, r.death_year, " +
            "r.balad_wafa, r.bukhari, r.muslim, r.marweyaat"

    private fun readRawi(c: Cursor) = Narrator(
        id = c.getLong(0), name = c.getString(1), shohra = c.getString(2), konya = c.getString(3),
        lakab = c.getString(4), rotba = if (c.isNull(5)) null else c.getInt(5), wasfRotba = c.getString(6),
        tabaka = if (c.isNull(7)) null else c.getInt(7), deathYear = c.getString(8), baladWafa = c.getString(9),
        bukhari = !c.isNull(10) && c.getInt(10) == 1, muslim = !c.isNull(11) && c.getInt(11) == 1,
        marweyaat = if (c.isNull(12)) null else c.getInt(12)
    )

    /** رجال إسناد الحديث بترتيب ورودهم */
    fun narratorsOf(hadithId: Long): List<Narrator> =
        db.corpus.rawQuery(
            "SELECT $RAWI_COLS FROM hadith_rawis hr JOIN rawis r ON r.id=hr.rawi_id " +
                "WHERE hr.hadith_id=? ORDER BY hr.pos", arrayOf(hadithId.toString())
        ).use { c ->
            val out = ArrayList<Narrator>()
            while (c.moveToNext()) out.add(readRawi(c))
            out
        }

    fun sahabaOf(hadithId: Long): List<Narrator> =
        db.corpus.rawQuery(
            "SELECT $RAWI_COLS FROM hadith_sahaba hs JOIN rawis r ON r.id=hs.rawi_id WHERE hs.hadith_id=?",
            arrayOf(hadithId.toString())
        ).use { c ->
            val out = ArrayList<Narrator>()
            while (c.moveToNext()) out.add(readRawi(c))
            out
        }

    fun rawi(id: Long): Narrator? =
        db.corpus.rawQuery("SELECT $RAWI_COLS FROM rawis r WHERE r.id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readRawi(it) else null }

    fun searchRawis(query: String, limit: Int = 40): List<Narrator> {
        val toks = org.murabbie.muhaddith.search.ArabicText.tokens(query).filter { it.length > 1 }
        if (toks.isEmpty()) return emptyList()
        val where = toks.joinToString(" AND ") { "r.name_norm LIKE ?" }
        val args = toks.map { "%$it%" } + listOf(limit.toString())
        return db.corpus.rawQuery(
            "SELECT $RAWI_COLS FROM rawis r WHERE $where ORDER BY r.marweyaat DESC LIMIT ?", args.toTypedArray()
        ).use { c ->
            val out = ArrayList<Narrator>()
            while (c.moveToNext()) out.add(readRawi(c))
            out
        }
    }

    // ---------- الكتب ----------

    fun books(): List<Book> =
        db.corpus.rawQuery(
            "SELECT id, title, author, death_year, priority, hadith_count FROM books ORDER BY priority, title", null
        ).use { c ->
            val out = ArrayList<Book>()
            while (c.moveToNext()) out.add(
                Book(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5))
            )
            out
        }

    // ---------- مجموعات المستخدم (مفتاحها bhid ليثبت عبر الحزم) ----------

    fun collections(): List<SavedCollection> =
        db.userDb.rawQuery(
            "SELECT c.id, c.name, (SELECT COUNT(*) FROM collection_items i WHERE i.collection_id=c.id) " +
                "FROM collections c ORDER BY c.created_at DESC", null
        ).use { c ->
            val out = ArrayList<SavedCollection>()
            while (c.moveToNext()) out.add(SavedCollection(c.getLong(0), c.getString(1), c.getInt(2)))
            out
        }

    fun createCollection(name: String): Long {
        val v = ContentValues().apply { put("name", name.trim()); put("created_at", System.currentTimeMillis()) }
        return db.userDb.insertWithOnConflict("collections", null, v, 4)
    }

    fun deleteCollection(id: Long) {
        db.userDb.delete("collection_items", "collection_id=?", arrayOf(id.toString()))
        db.userDb.delete("collections", "id=?", arrayOf(id.toString()))
    }

    fun addToCollection(collectionId: Long, bhid: String) {
        val v = ContentValues().apply {
            put("collection_id", collectionId); put("bhid", bhid); put("added_at", System.currentTimeMillis())
        }
        db.userDb.insertWithOnConflict("collection_items", null, v, 5)
    }

    fun removeFromCollection(collectionId: Long, bhid: String) {
        db.userDb.delete("collection_items", "collection_id=? AND bhid=?", arrayOf(collectionId.toString(), bhid))
    }

    fun collectionItems(collectionId: Long): List<Hadith> {
        val bhids = db.userDb.rawQuery(
            "SELECT bhid FROM collection_items WHERE collection_id=? ORDER BY added_at DESC",
            arrayOf(collectionId.toString())
        ).use { c -> val o = ArrayList<String>(); while (c.moveToNext()) o.add(c.getString(0)); o }
        return bhids.mapNotNull { hadithByBhid(it) }
    }

    fun collectionsContaining(bhid: String): Set<Long> =
        db.userDb.rawQuery("SELECT collection_id FROM collection_items WHERE bhid=?", arrayOf(bhid))
            .use { c -> val o = HashSet<Long>(); while (c.moveToNext()) o.add(c.getLong(0)); o }

    // ---------- خطط البحث المحفوظة ----------

    data class SavedSearch(val id: Long, val name: String, val query: String, val engines: Set<Engine>, val scope: SearchScope)

    fun saveSearch(name: String, query: String, engines: Set<Engine>, scope: SearchScope) {
        val v = ContentValues().apply {
            put("name", name); put("query", query)
            put("engines", engines.joinToString(",") { it.name }); put("scope", scope.name)
            put("created_at", System.currentTimeMillis())
        }
        db.userDb.insert("saved_searches", null, v)
    }

    fun savedSearches(): List<SavedSearch> =
        db.userDb.rawQuery("SELECT id, name, query, engines, scope FROM saved_searches ORDER BY created_at DESC", null)
            .use { c ->
                val out = ArrayList<SavedSearch>()
                while (c.moveToNext()) {
                    val eng = (c.getString(3) ?: "").split(",").mapNotNull { s -> runCatching { Engine.valueOf(s) }.getOrNull() }.toSet()
                    val sc = runCatching { SearchScope.valueOf(c.getString(4) ?: "") }.getOrNull() ?: SearchScope.MATN
                    out.add(SavedSearch(c.getLong(0), c.getString(1), c.getString(2), eng, sc))
                }
                out
            }

    fun deleteSavedSearch(id: Long) { db.userDb.delete("saved_searches", "id=?", arrayOf(id.toString())) }

    fun recordHistory(query: String) {
        if (query.isBlank()) return
        db.userDb.delete("history", "query=?", arrayOf(query))
        val v = ContentValues().apply { put("query", query); put("at", System.currentTimeMillis()) }
        db.userDb.insert("history", null, v)
        db.userDb.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY at DESC LIMIT 30)")
    }

    fun history(limit: Int = 8): List<String> =
        db.userDb.rawQuery("SELECT query FROM history ORDER BY at DESC LIMIT ?", arrayOf(limit.toString()))
            .use { c -> val o = ArrayList<String>(); while (c.moveToNext()) o.add(c.getString(0)); o }
}

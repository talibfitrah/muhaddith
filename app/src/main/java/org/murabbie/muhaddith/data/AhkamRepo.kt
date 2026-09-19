package org.murabbie.muhaddith.data

import android.database.Cursor
import org.murabbie.muhaddith.search.ArabicText
import org.murabbie.muhaddith.search.PassageText
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * استعلامات حزمة «أحكام العلماء ومختلف الحديث» (muhaddith_ahkam.db).
 * كل الدوال تعيد قوائم فارغة إن لم تكن الحزمة مثبَّتة.
 */
class AhkamRepo(private val db: MuhaddithDatabase) {

    private val a: SQLiteDatabase? get() = db.ahkam
    val installed: Boolean get() = a != null

    fun info(): AhkamInfo = db.ahkamInfo()

    private fun inflate(blob: ByteArray?): String {
        if (blob == null || blob.isEmpty()) return ""
        val inf = Inflater(); inf.setInput(blob)
        val out = ByteArrayOutputStream(blob.size * 4); val buf = ByteArray(8192)
        try { while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) } } finally { inf.end() }
        return out.toString("UTF-8")
    }

    // ---------- الأحكام ----------

    private val RULING_SELECT = "SELECT r.id, r.bhid, r.cluster_id, s.name, s.death, r.grade, r.level, r.source, r.ref, r.quote, r.origin, r.section_id, r.via_bhid, r.conf " +
        "FROM rulings r JOIN scholars s ON s.id = r.scholar_id "

    private fun readRuling(c: Cursor) = Ruling(
        id = c.getLong(0), bhid = c.getString(1), clusterId = if (c.isNull(2)) null else c.getLong(2),
        scholar = c.getString(3), death = if (c.isNull(4)) null else c.getInt(4), grade = c.getString(5),
        level = if (c.isNull(6)) null else c.getInt(6), source = c.getString(7), ref = c.getString(8), quote = c.getString(9),
        origin = c.getString(10) ?: "", sectionId = if (c.isNull(11)) null else c.getLong(11), viaBhid = c.getString(12),
        conf = if (c.isNull(13)) 1.0 else c.getDouble(13)
    )

    private fun rulings(sql: String, args: Array<String>): List<Ruling> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery(sql, args).use { c -> val o = ArrayList<Ruling>(); while (c.moveToNext()) o.add(readRuling(c)); o }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * أحكام حديث بعينه ثم أحكام طرقه الأخرى (العنقود نفسه) التي تبلغ ثقة ربطها الحدّ المطلوب، مرتَّبة بوفاة العالم.
     * [precision] من [RulingPrecision]: في «دقيق جدًّا» لا تُعرض أحكام الطرق الأخرى إلا بمطابقة قوية جدًّا.
     */
    fun rulingsFor(bhid: String, clusterId: Long?, precision: Int = RulingPrecision.PRECISE): RulingsBundle {
        val order = " ORDER BY COALESCE(s.death, 9999), s.name, r.origin"
        val own = rulings(RULING_SELECT + "WHERE r.bhid=?$order", arrayOf(bhid))
        val min = RulingPrecision.minConf(precision)
        val others = if (clusterId == null) emptyList() else
            rulings(RULING_SELECT + "WHERE r.cluster_id=? AND (r.bhid IS NULL OR r.bhid<>?) AND r.conf>=?$order LIMIT 400",
                arrayOf(clusterId.toString(), bhid, min.toString()))
        return RulingsBundle(own, others)
    }

    /** عدد الأحكام على حديث (للعرض المختصر في القوائم) */
    fun rulingCount(bhid: String, clusterId: Long?): Int {
        val d = a ?: return 0
        return try {
            d.rawQuery("SELECT COUNT(*) FROM rulings WHERE bhid=? OR cluster_id=?", arrayOf(bhid, (clusterId ?: -1).toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }

    /** «صحّحه ٣ · حسّنه ١ · ضعّفه ٢» — عدد العلماء (لا الأحكام) في كل درجة على الحديث وطرقه */
    fun summaryFor(bhid: String, clusterId: Long?, precision: Int = RulingPrecision.PRECISE): String? {
        val d = a ?: return null
        return try {
            d.rawQuery("SELECT CASE WHEN level=0 THEN 0 WHEN level=1 THEN 1 WHEN level IS NULL THEN -1 ELSE 2 END b, COUNT(DISTINCT scholar_id) " +
                "FROM rulings WHERE bhid=? OR (cluster_id=? AND conf>=?) GROUP BY b",
                arrayOf(bhid, (clusterId ?: -1).toString(), RulingPrecision.minConf(precision).toString())).use { c ->
                val m = HashMap<Int, Int>(); while (c.moveToNext()) m[c.getInt(0)] = c.getInt(1)
                val parts = listOfNotNull(
                    m[0]?.let { "صحّحه ${ArabicText.arabicDigits(it)}" }, m[1]?.let { "حسّنه ${ArabicText.arabicDigits(it)}" }, m[2]?.let { "ضعّفه ${ArabicText.arabicDigits(it)}" })
                parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
        } catch (_: Exception) { null }
    }

    fun scholars(): List<Scholar> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT id, name, death, kind, rulings FROM scholars ORDER BY COALESCE(death, 9999), name", null).use { c ->
                val o = ArrayList<Scholar>(); while (c.moveToNext()) o.add(Scholar(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2), c.getString(3) ?: "", c.getInt(4))); o
            }
        } catch (_: Exception) { emptyList() }
    }

    /** أحكام عالم معيَّن (لتصفّح أحكامه) */
    fun rulingsOfScholar(scholarId: Long, offset: Int, limit: Int = 50): List<Ruling> =
        rulings(RULING_SELECT + "WHERE r.scholar_id=? ORDER BY r.id LIMIT ? OFFSET ?", arrayOf(scholarId.toString(), limit.toString(), offset.toString()))

    // ---------- الكتب والمقاطع ----------

    private fun readBook(c: Cursor) = MkBook(
        c.getLong(0), c.getString(1), c.getString(2) ?: "", if (c.isNull(3)) null else c.getInt(3), c.getString(4) ?: "",
        c.getString(5), c.getString(6), c.getString(7), c.getInt(8), c.getInt(9), c.getInt(10)
    )
    private val BOOK_COLS = "id, title, author, death, kind, edition, editor, publisher, vols, sections, words"

    fun books(): List<MkBook> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT $BOOK_COLS FROM mk_books ORDER BY ord", null).use { c -> val o = ArrayList<MkBook>(); while (c.moveToNext()) o.add(readBook(c)); o }
        } catch (_: Exception) { emptyList() }
    }

    fun book(id: Long): MkBook? {
        val d = a ?: return null
        return try { d.rawQuery("SELECT $BOOK_COLS FROM mk_books WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) readBook(it) else null } } catch (_: Exception) { null }
    }

    private val SEC_COLS = "id, book_id, ord, title, vol, page_start, page_end, words, text"
    private fun readSection(c: Cursor, withText: Boolean) = MkSection(
        c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), if (c.isNull(4)) null else c.getInt(4),
        if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6), c.getInt(7),
        if (withText) inflate(c.getBlob(8)) else ""
    )

    /** فهرس مقاطع كتاب (بلا نصوص) */
    fun sections(bookId: Long, offset: Int, limit: Int = 200): List<MkSection> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT id, book_id, ord, title, vol, page_start, page_end, words, NULL FROM mk_sections WHERE book_id=? ORDER BY ord LIMIT ? OFFSET ?",
                arrayOf(bookId.toString(), limit.toString(), offset.toString())).use { c -> val o = ArrayList<MkSection>(); while (c.moveToNext()) o.add(readSection(c, false)); o }
        } catch (_: Exception) { emptyList() }
    }

    fun section(id: Long): MkSection? {
        val d = a ?: return null
        return try { d.rawQuery("SELECT $SEC_COLS FROM mk_sections WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) readSection(it, true) else null } } catch (_: Exception) { null }
    }

    /** المقطع التالي/السابق في الكتاب نفسه */
    fun neighbor(sec: MkSection, next: Boolean): Long? {
        val d = a ?: return null
        val sql = if (next) "SELECT id FROM mk_sections WHERE book_id=? AND ord>? ORDER BY ord LIMIT 1"
                  else "SELECT id FROM mk_sections WHERE book_id=? AND ord<? ORDER BY ord DESC LIMIT 1"
        return try { d.rawQuery(sql, arrayOf(sec.bookId.toString(), sec.ord.toString())).use { if (it.moveToFirst()) it.getLong(0) else null } } catch (_: Exception) { null }
    }

    /** أول مقطع يبدأ عند الصفحة المطلوبة أو بعدها */
    fun sectionAtPage(bookId: Long, vol: Int?, page: Int): Long? {
        val d = a ?: return null
        return try {
            d.rawQuery("SELECT id FROM mk_sections WHERE book_id=? AND (? IS NULL OR vol=?) AND page_end>=? ORDER BY ord LIMIT 1",
                arrayOf(bookId.toString(), vol?.toString(), vol?.toString(), page.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (_: Exception) { null }
    }

    /** الأحاديث (في حزمة المتون) التي يقتبسها مقطع: تعيد bhid مع عدد الشظايا المتطابقة */
    fun sectionHadiths(sectionId: Long): List<Pair<String, Int>> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT bhid, MAX(hits) FROM mk_links WHERE section_id=? GROUP BY cluster_id ORDER BY 2 DESC LIMIT 40", arrayOf(sectionId.toString())).use { c ->
                val o = ArrayList<Pair<String, Int>>(); while (c.moveToNext()) o.add(c.getString(0) to c.getInt(1)); o
            }
        } catch (_: Exception) { emptyList() }
    }

    /** مواضع الكلام على حديث مجمَّع في كتب مختلف الحديث والتخريج */
    fun passagesFor(clusterId: Long?, withSnippet: Boolean = true, precision: Int = RulingPrecision.PRECISE): List<PassageRef> {
        if (clusterId == null) return emptyList()
        val d = a ?: return emptyList()
        return try {
            d.rawQuery(
                "SELECT l.section_id, b.id, b.title, b.author, b.kind, s.title, s.vol, s.page_start, MAX(l.hits), MIN(l.para), s.text, MAX(l.conf) " +
                    "FROM mk_links l JOIN mk_sections s ON s.id=l.section_id JOIN mk_books b ON b.id=s.book_id " +
                    "WHERE l.cluster_id=? AND l.conf>=? GROUP BY l.section_id ORDER BY b.ord, s.ord LIMIT 120",
                arrayOf(clusterId.toString(), RulingPrecision.minConf(precision).toString())
            ).use { c ->
                val o = ArrayList<PassageRef>()
                while (c.moveToNext()) {
                    val snippet = if (withSnippet) {
                        val text = inflate(c.getBlob(10)); val para = c.getInt(9)
                        val p = text.split('\n').getOrNull(para) ?: text
                        val t = ArabicText.stripDiacritics(p)
                        if (t.length > 220) t.take(220) + "…" else t
                    } else null
                    o.add(PassageRef(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "",
                        c.getString(5), if (c.isNull(6)) null else c.getInt(6), if (c.isNull(7)) null else c.getInt(7), c.getInt(8), snippet,
                        if (c.isNull(11)) 0.7 else c.getDouble(11)))
                }
                o
            }
        } catch (_: Exception) { emptyList() }
    }

    fun passageCount(clusterId: Long?): Int {
        if (clusterId == null) return 0
        val d = a ?: return 0
        return try { d.rawQuery("SELECT COUNT(DISTINCT section_id) FROM mk_links WHERE cluster_id=?", arrayOf(clusterId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 }
    }

    // ---------- البحث في كتب مختلف الحديث ----------

    fun searchPassages(query: String, bookId: Long? = null, limit: Int = 60): List<PassageHit> {
        val d = a ?: return emptyList()
        val toks = ArabicText.tokens(query).map { ArabicText.stripDiacritics(it) }.filter { it.length > 1 }
        if (toks.isEmpty()) return emptyList()
        val phrase = query.trim().startsWith("\"") && query.trim().endsWith("\"") && toks.size > 1
        val fts = if (phrase) "\"" + toks.joinToString(" ") { it.replace("\"", "") } + "\""
                  else toks.joinToString(" AND ") { "\"" + it.replace("\"", "") + "\"" }
        val sql = "SELECT s.id, s.book_id, s.ord, s.title, s.vol, s.page_start, s.page_end, s.words, s.text, b.title, b.author " +
            "FROM mk_fts f JOIN mk_sections s ON s.id=f.rowid JOIN mk_books b ON b.id=s.book_id WHERE mk_fts MATCH ? " +
            (if (bookId != null) "AND s.book_id=? " else "") + "ORDER BY bm25(mk_fts, 2.0, 1.0) LIMIT ?"
        val args = if (bookId != null) arrayOf(fts, bookId.toString(), limit.toString()) else arrayOf(fts, limit.toString())
        return try {
            d.rawQuery(sql, args).use { c ->
                val o = ArrayList<PassageHit>()
                while (c.moveToNext()) {
                    val sec = readSection(c, true)
                    o.add(PassageHit(sec.copy(text = ""), c.getString(9), c.getString(10) ?: "", snippet(sec.text, toks)))
                }
                o
            }
        } catch (_: Exception) { emptyList() }
    }

    fun snippet(text: String, toks: List<String>, len: Int = 240): String = PassageText.snippet(text, toks, len)
}

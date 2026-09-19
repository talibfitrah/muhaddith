package org.murabbie.muhaddith.data

import android.database.Cursor
import org.murabbie.muhaddith.search.ArabicText
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** استعلامات حزمة «شروح الحديث وأسباب وروده» (muhaddith_shuruh.db) */
class ShuruhRepo(private val db: MuhaddithDatabase, private val hadithByBhid: (String) -> Hadith?) {

    private val a: SQLiteDatabase? get() = db.shuruh
    val installed: Boolean get() = a != null
    fun info(): ShuruhInfo = db.shuruhInfo()

    private fun inflate(blob: ByteArray?): String {
        if (blob == null || blob.isEmpty()) return ""
        val inf = Inflater(); inf.setInput(blob)
        val out = ByteArrayOutputStream(blob.size * 4); val buf = ByteArray(8192)
        try { while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) } } finally { inf.end() }
        return out.toString("UTF-8")
    }

    private val POINT_SELECT = "SELECT p.id, p.cluster_id, p.section_id, p.book_id, b.title, b.author, p.scholar, p.death, p.tabaka, p.category, p.cat_conf, p.text, p.is_author, p.conf, s.vol, s.page_start, p.kind " +
        "FROM sh_points p JOIN sh_books b ON b.id=p.book_id JOIN sh_sections s ON s.id=p.section_id "

    private fun readPoint(c: Cursor) = SharhPoint(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5) ?: "", c.getString(6),
        if (c.isNull(7)) null else c.getInt(7), if (c.isNull(8)) null else c.getInt(8), c.getString(9) ?: "أخرى", c.getDouble(10),
        c.getString(11), c.getInt(12) == 1, c.getDouble(13), if (c.isNull(14)) null else c.getInt(14), if (c.isNull(15)) null else c.getInt(15),
        c.getString(16) ?: "qawl"
    )

    /** المجموعة الدقيقة للرواية داخل عنقودها الضخم (null = عنقود صغير، العنقود كله هو المجموعة) */
    fun fineOf(bhid: String?): Long? {
        if (bhid == null) return null
        val d = a ?: return null
        return try { d.rawQuery("SELECT fine_id FROM sh_fine WHERE bhid=?", arrayOf(bhid)).use { if (it.moveToFirst()) it.getLong(0) else null } } catch (_: Exception) { null }
    }

    /** شرط الربط بحسب الدقة: في «دقيق» و«صارم» لا يُعرض إلا ما رُبط برواية من المجموعة الدقيقة نفسها (متن الحديث بعينه)؛ وفي «واسع» العنقود كله */
    private fun fineClause(prefix: String, fine: Long?, precision: Int): Pair<String, List<String>> =
        if (fine == null || precision == RulingPrecision.WIDE) "" to emptyList()
        else " AND (${prefix}fine_id=? OR ${prefix}fine_id IS NULL)" to listOf(fine.toString())

    /** استنباطات الحديث بحسب دقة الربط، مرتَّبة: الصحابة ← التابعون ← … ثم بالوفاة */
    fun pointsFor(clusterId: Long?, precision: Int = RulingPrecision.PRECISE, limit: Int = 600, bhid: String? = null): List<SharhPoint> {
        if (clusterId == null) return emptyList()
        val d = a ?: return emptyList()
        val fine = fineOf(bhid)
        fun run(fc: String, fa: List<String>): List<SharhPoint> = try {
            d.rawQuery(POINT_SELECT + "WHERE p.cluster_id=? AND p.conf>=?$fc ORDER BY COALESCE(p.death, 99999), p.scholar, p.is_author, p.id LIMIT ?",
                (listOf(clusterId.toString(), RulingPrecision.minConf(precision).toString()) + fa + listOf(limit.toString())).toTypedArray()).use { c ->
                val o = ArrayList<SharhPoint>(); while (c.moveToNext()) o.add(readPoint(c)); o
            }.sortedWith(compareBy({ it.layer }, { it.death ?: 99999 }, { it.scholar }, { it.isAuthor }))
        } catch (_: Exception) { emptyList() }
        val (fc, fa) = fineClause("p.", fine, precision)
        val res = run(fc, fa)
        // احتياط: إن أخلى تقييدُ المجموعة الدقيقة الشاشةَ (رواية هذا الحديث في مجموعة والشرح في أخرى) نعرض العنقود كله كي لا تبقى بلا شروح — إلا في «دقيق جدًّا» فيبقى صارمًا
        val out = if (res.isEmpty() && fc.isNotEmpty() && precision != RulingPrecision.STRICT) run("", emptyList()) else res
        return attachSources(out)
    }

    /** مصادر كل نقطة (الأثر المدمَج من كتب عدة يحمل أكثر من مصدر) */
    private fun attachSources(points: List<SharhPoint>): List<SharhPoint> {
        val d = a ?: return points
        if (points.isEmpty()) return points
        val ids = points.map { it.id }
        val map = HashMap<Long, ArrayList<PointSource>>()
        try {
            ids.chunked(400).forEach { chunk ->
                val ph = chunk.joinToString(",") { "?" }
                d.rawQuery("SELECT ps.point_id, b.id, b.title, b.author, s.vol, s.page_start, s.id FROM sh_point_sources ps JOIN sh_books b ON b.id=ps.book_id JOIN sh_sections s ON s.id=ps.section_id WHERE ps.point_id IN ($ph) ORDER BY b.ord, s.ord",
                    chunk.map { it.toString() }.toTypedArray()).use { c ->
                    while (c.moveToNext()) map.getOrPut(c.getLong(0)) { ArrayList() }.add(PointSource(c.getLong(1), c.getString(2), c.getString(3) ?: "", if (c.isNull(4)) null else c.getInt(4), if (c.isNull(5)) null else c.getInt(5), c.getLong(6)))
                }
            }
        } catch (_: Exception) { return points }
        return points.map { p -> map[p.id]?.let { p.copy(sources = it) } ?: p }
    }

    fun pointCount(clusterId: Long?, precision: Int = RulingPrecision.PRECISE): Int {
        if (clusterId == null) return 0
        val d = a ?: return 0
        return try { d.rawQuery("SELECT COUNT(*) FROM sh_points WHERE cluster_id=? AND conf>=?", arrayOf(clusterId.toString(), RulingPrecision.minConf(precision).toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 }
    }

    /** روايات المتن التي تحمل سياق الحديث وقصته (الثابتة: من الصحيحين أو بحكم صحيح/حسن) */
    fun storiesFor(clusterId: Long?, exclude: String? = null): List<Story> {
        if (clusterId == null) return emptyList()
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT bhid, score FROM stories WHERE cluster_id=? ORDER BY score DESC LIMIT 3", arrayOf(clusterId.toString())).use { c ->
                val o = ArrayList<Story>()
                while (c.moveToNext()) { val b = c.getString(0); if (b != exclude) hadithByBhid(b)?.let { o.add(Story(it, c.getInt(1))) } }
                o
            }
        } catch (_: Exception) { emptyList() }
    }

    /** مواضع الحديث في كتب الشروح وأسباب الورود (بحسب النوع) */
    fun sectionsFor(clusterId: Long?, kinds: List<String>, precision: Int = RulingPrecision.PRECISE, withSnippet: Boolean = true, bhid: String? = null): List<PassageRef> {
        if (clusterId == null) return emptyList()
        val d = a ?: return emptyList()
        val ph = kinds.joinToString(",") { "?" }
        val fine = fineOf(bhid)
        fun run(fc: String, fa: List<String>): List<PassageRef> = try {
            d.rawQuery(
                "SELECT l.section_id, b.id, b.title, b.author, b.kind, s.title, s.vol, s.page_start, MAX(l.hits), MIN(l.para), s.text, MAX(l.conf), b.death " +
                    "FROM sh_links l JOIN sh_sections s ON s.id=l.section_id JOIN sh_books b ON b.id=s.book_id " +
                    "WHERE l.cluster_id=? AND l.conf>=?$fc AND b.kind IN ($ph) GROUP BY l.section_id ORDER BY b.ord, s.ord LIMIT 300",
                (listOf(clusterId.toString(), RulingPrecision.minConf(precision).toString()) + fa + kinds).toTypedArray()
            ).use { c ->
                val o = ArrayList<PassageRef>()
                while (c.moveToNext()) {
                    val minPara = c.getInt(9)
                    val snippet = if (withSnippet) {
                        val text = inflate(c.getBlob(10)); val para = maxOf(0, minPara)
                        val p = text.split('\n').getOrNull(para) ?: text
                        val t = ArabicText.stripDiacritics(p); if (t.length > 220) t.take(220) + "…" else t
                    } else null
                    o.add(PassageRef(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5),
                        if (c.isNull(6)) null else c.getInt(6), if (c.isNull(7)) null else c.getInt(7), c.getInt(8), snippet, if (c.isNull(11)) 0.7 else c.getDouble(11), primary = minPara < 0,
                        death = if (c.isNull(12)) null else c.getInt(12)))
                }
                o
            }
        } catch (_: Exception) { emptyList() }
        val (fc, fa) = fineClause("l.", fine, precision)
        val res = run(fc, fa)
        return if (res.isEmpty() && fc.isNotEmpty() && precision != RulingPrecision.STRICT) run("", emptyList()) else res
    }

    /** نص وحدة شرح مقسَّمًا نقاطًا مرقَّمة: كل فقرة (أو جملة طويلة) نقطة، بلا الأسانيد ولا الشذرات */
    fun unitPoints(sectionId: Long, maxPoints: Int = 40, maxWords: Int = 110): List<String> {
        val sec = section(sectionId) ?: return emptyList()
        val isnad = Regex("(?:^|\\s)(?:و?حدثنا|و?حدثن[يى]|و?أخبرنا|و?أخبرن[يى]|ثنا|أنبأنا)(?:\\s|$)")
        val html = Regex("</?[A-Za-z][^<>]{0,120}>|&nbsp;|&amp;")
        val out = ArrayList<String>()
        for (para0 in sec.text.split('\n')) {
            val para = html.replace(para0, " ").trim()
            if (para.isBlank()) continue
            // فقرة طويلة تُقسَّم عند نهايات الجمل
            val pieces = if (para.split(' ').size > maxWords) para.split(Regex("(?<=[.؛!؟])\\s+")) else listOf(para)
            var buf = StringBuilder()
            fun flush() { val t = buf.toString().trim(); if (t.isNotEmpty()) out.add(t); buf = StringBuilder() }
            for (pc in pieces) {
                if (buf.isNotEmpty() && (buf.length + pc.length) / 6 > maxWords) flush()
                buf.append(if (buf.isEmpty()) pc else " $pc")
            }
            flush()
        }
        return out.filter { t ->
            val w = t.split(' ').size
            w >= 6 && isnad.findAll(t.take(80)).count() == 0
        }.take(maxPoints)
    }

    // ---------- الكتب والمقاطع ----------
    private val BOOK_COLS = "id, title, author, death, kind, edition, editor, publisher, vols, sections, words"
    private fun readBook(c: Cursor) = MkBook(c.getLong(0), c.getString(1), c.getString(2) ?: "", if (c.isNull(3)) null else c.getInt(3), c.getString(4) ?: "",
        c.getString(5), c.getString(6), c.getString(7), c.getInt(8), c.getInt(9), c.getInt(10))

    fun books(): List<MkBook> {
        val d = a ?: return emptyList()
        return try { d.rawQuery("SELECT $BOOK_COLS FROM sh_books ORDER BY ord", null).use { c -> val o = ArrayList<MkBook>(); while (c.moveToNext()) o.add(readBook(c)); o } } catch (_: Exception) { emptyList() }
    }
    fun book(id: Long): MkBook? {
        val d = a ?: return null
        return try { d.rawQuery("SELECT $BOOK_COLS FROM sh_books WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) readBook(it) else null } } catch (_: Exception) { null }
    }
    private fun readSection(c: Cursor, withText: Boolean) = MkSection(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), if (c.isNull(4)) null else c.getInt(4),
        if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6), c.getInt(7), if (withText) inflate(c.getBlob(8)) else "")

    fun sections(bookId: Long, offset: Int, limit: Int = 5000): List<MkSection> {
        val d = a ?: return emptyList()
        return try {
            d.rawQuery("SELECT id, book_id, ord, title, vol, page_start, page_end, words, NULL FROM sh_sections WHERE book_id=? ORDER BY ord LIMIT ? OFFSET ?",
                arrayOf(bookId.toString(), limit.toString(), offset.toString())).use { c -> val o = ArrayList<MkSection>(); while (c.moveToNext()) o.add(readSection(c, false)); o }
        } catch (_: Exception) { emptyList() }
    }
    fun section(id: Long): MkSection? {
        val d = a ?: return null
        return try { d.rawQuery("SELECT id, book_id, ord, title, vol, page_start, page_end, words, text FROM sh_sections WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) readSection(it, true) else null } } catch (_: Exception) { null }
    }
    fun neighbor(sec: MkSection, next: Boolean): Long? {
        val d = a ?: return null
        val sql = if (next) "SELECT id FROM sh_sections WHERE book_id=? AND ord>? ORDER BY ord LIMIT 1" else "SELECT id FROM sh_sections WHERE book_id=? AND ord<? ORDER BY ord DESC LIMIT 1"
        return try { d.rawQuery(sql, arrayOf(sec.bookId.toString(), sec.ord.toString())).use { if (it.moveToFirst()) it.getLong(0) else null } } catch (_: Exception) { null }
    }
    fun sectionAtPage(bookId: Long, vol: Int?, page: Int): Long? {
        val d = a ?: return null
        return try {
            d.rawQuery("SELECT id FROM sh_sections WHERE book_id=? AND (? IS NULL OR vol=?) AND page_end>=? ORDER BY ord LIMIT 1",
                arrayOf(bookId.toString(), vol?.toString(), vol?.toString(), page.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (_: Exception) { null }
    }
    /** الأحاديث التي يشرحها مقطع (أقوى الروابط) */
    fun sectionClusters(sectionId: Long): List<Long> {
        val d = a ?: return emptyList()
        return try { d.rawQuery("SELECT cluster_id FROM sh_links WHERE section_id=? ORDER BY hits DESC LIMIT 12", arrayOf(sectionId.toString())).use { c -> val o = ArrayList<Long>(); while (c.moveToNext()) o.add(c.getLong(0)); o } } catch (_: Exception) { emptyList() }
    }
}

package org.murabbie.muhaddith.search

import io.requery.android.database.sqlite.SQLiteDatabase
import org.murabbie.muhaddith.data.*

/**
 * البحث الهجين: يشغّل المحركات المختارة على فهرس FTS5، ثم يدمج نتائجها
 * بدمج الرتب المتبادل (Reciprocal Rank Fusion) مع أوزان لكل محرك.
 */
class SearchEngine(
    private val corpusProvider: () -> SQLiteDatabase,
    private val loader: (List<Long>) -> Map<Long, Hadith>,
    /** المحرك الدلالي: يعيد معرّفات مرتّبة بالمعنى، أو null إن لم يكن مثبَّتًا */
    private val semantic: ((String, Int) -> List<Long>?)? = null
) {
    /** وزن المحرك الدلالي في الدمج (يضبطه المستخدم من الإعدادات) */
    @Volatile var semanticWeight = 0.9
    private val corpus get() = corpusProvider()
    private val planner = QueryPlanner(corpusProvider)

    private val weights get() = mapOf(
        Engine.LITERAL to 1.00,
        Engine.MORPH to 0.72,
        Engine.SEMANTIC to semanticWeight
    )

    fun plan(
        raw: String, engines: Set<Engine>, scope: SearchScope,
        bookFilter: Long?, hokmFilter: Int?
    ): SearchPlan = planner.plan(raw, engines, scope, bookFilter, hokmFilter)

    fun search(plan: SearchPlan, limit: Int = 200, perEngineLimit: Int = 400, snippetLen: Int = 160): List<SearchHit> {
        val semanticOnly = plan.engines == setOf(Engine.SEMANTIC)
        if (plan.terms.none { !it.negated } && !semanticOnly) return emptyList()
        val perEngine = LinkedHashMap<Engine, List<Long>>()
        for (engine in listOf(Engine.LITERAL, Engine.MORPH)) {
            if (engine !in plan.engines) continue
            val ids = runFts(plan, engine, perEngineLimit)
            if (ids.isNotEmpty()) perEngine[engine] = ids
        }
        if (Engine.SEMANTIC in plan.engines && semantic != null && plan.raw.isNotBlank()) {
            val ids = semantic.invoke(plan.raw, perEngineLimit)
            if (ids != null) {
                val filtered = applyFilters(ids, plan)
                if (filtered.isNotEmpty()) perEngine[Engine.SEMANTIC] = filtered
            }
        }
        if (perEngine.isEmpty()) return emptyList()

        val fused = LinkedHashMap<Long, Double>()
        val by = LinkedHashMap<Long, MutableSet<Engine>>()
        val k = 20.0
        perEngine.forEach { (engine, ids) ->
            val w = weights[engine] ?: 0.5
            ids.forEachIndexed { rank, id ->
                fused[id] = (fused[id] ?: 0.0) + w * (1.0 / (k + rank + 1))
                by.getOrPut(id) { linkedSetOf() }.add(engine)
            }
        }
        val ordered = fused.entries.sortedByDescending { it.value }.take(limit)
        val hadiths = loader(ordered.map { it.key })
        val highlight = highlightTerms(plan)
        return ordered.mapNotNull { e ->
            val h = hadiths[e.key] ?: return@mapNotNull null
            SearchHit(
                hadith = h,
                score = e.value,
                engines = by[e.key] ?: emptySet(),
                snippet = makeSnippet(h.matn, highlight, snippetLen)
            )
        }
    }

    private fun runFts(plan: SearchPlan, engine: Engine, limit: Int): List<Long> {
        val expr = planner.ftsExpression(plan, engine) ?: return emptyList()
        val sb = StringBuilder(
            "SELECT f.rowid FROM hadith_fts f JOIN hadiths h ON h.id = f.rowid WHERE hadith_fts MATCH ?"
        )
        val args = mutableListOf(expr)
        plan.bookFilter?.let { sb.append(" AND h.book_id = ?"); args.add(it.toString()) }
        plan.hokmFilter?.let { sb.append(" AND h.hokm = ?"); args.add(it.toString()) }
        if (plan.bookScope.isNotEmpty() && plan.bookFilter == null) {
            sb.append(" AND h.book_id IN (").append(plan.bookScope.joinToString(",") { "?" }).append(")")
            plan.bookScope.forEach { args.add(it.toString()) }
        }
        sb.append(" ORDER BY bm25(hadith_fts, 1.0, 0.6, 0.9), h.book_id, h.hadith_num LIMIT ?")
        args.add(limit.toString())
        return try {
            corpus.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
                val out = ArrayList<Long>(c.count)
                while (c.moveToNext()) out.add(c.getLong(0))
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** يطبّق مرشّحات الكتاب/الحكم/النطاق على قائمة معرّفات من المحرك الدلالي */
    private fun applyFilters(ids: List<Long>, plan: SearchPlan): List<Long> {
        if (plan.bookFilter == null && plan.hokmFilter == null && plan.bookScope.isEmpty()) return ids
        val keep = HashSet<Long>()
        ids.chunked(400).forEach { chunk ->
            val sb = StringBuilder("SELECT id FROM hadiths WHERE id IN (").append(chunk.joinToString(",") { "?" }).append(")")
            val args = chunk.map { it.toString() }.toMutableList()
            plan.bookFilter?.let { sb.append(" AND book_id = ?"); args.add(it.toString()) }
            plan.hokmFilter?.let { sb.append(" AND hokm = ?"); args.add(it.toString()) }
            if (plan.bookScope.isNotEmpty() && plan.bookFilter == null) {
                sb.append(" AND book_id IN (").append(plan.bookScope.joinToString(",") { "?" }).append(")"); plan.bookScope.forEach { args.add(it.toString()) }
            }
            try { corpus.rawQuery(sb.toString(), args.toTypedArray()).use { c -> while (c.moveToNext()) keep.add(c.getLong(0)) } } catch (_: Exception) { }
        }
        return ids.filter { it in keep }
    }

    /** الكلمات التي تُبرَز في المتن: اللفظ، وجذعه، ومفاتيحه الصرفية */
    fun highlightTerms(plan: SearchPlan): List<String> {
        val out = linkedSetOf<String>()
        plan.terms.filter { !it.negated }.forEach { t ->
            out.add(t.normalized)
            out.add(ArabicText.stripArticle(t.normalized))
            if (Engine.MORPH in plan.engines || Engine.SEMANTIC in plan.engines) {
                out.add(t.stem); out.addAll(t.roots)
            }
            if (Engine.SEMANTIC in plan.engines) out.addAll(t.synonymKeys)
        }
        return out.filter { it.length > 1 }
    }

    private fun makeSnippet(matn: String, terms: List<String>, window: Int = 160): String {
        if (matn.length <= window) return matn
        val words = matn.split(' ')
        var hitIndex = -1
        for ((i, w) in words.withIndex()) {
            val n = ArabicText.normalize(w)
            if (n.isEmpty()) continue
            val st = ArabicText.stripArticle(n)
            if (terms.any { t -> n == t || st == t || (t.length >= 3 && (n.startsWith(t) || st.startsWith(t))) }) {
                hitIndex = i; break
            }
        }
        if (hitIndex < 0) return matn.take(window).substringBeforeLast(' ') + "…"
        var start = hitIndex
        var len = 0
        while (start > 0 && len < window / 3) { start--; len += words[start].length + 1 }
        val sb = StringBuilder()
        if (start > 0) sb.append("…")
        var i = start
        while (i < words.size && sb.length < window) { sb.append(words[i]).append(' '); i++ }
        if (i < words.size) sb.append("…")
        return sb.toString().trim()
    }
}

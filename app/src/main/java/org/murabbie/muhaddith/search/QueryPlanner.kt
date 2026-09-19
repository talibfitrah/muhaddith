package org.murabbie.muhaddith.search

import io.requery.android.database.sqlite.SQLiteDatabase
import org.murabbie.muhaddith.data.Engine
import org.murabbie.muhaddith.data.QueryTerm
import org.murabbie.muhaddith.data.SearchPlan
import org.murabbie.muhaddith.data.SearchScope

/**
 * يحوّل نصّ المستخدم إلى خطة بحث: تطبيع، ثم توسيع صرفي بمعجم المحدِّث
 * (جدول morph: كلمة مطبَّعة → جذر، أو وزن لغير المشتق).
 *
 * صيغة الاستعلام: الكلمات بينها «و» ضمنية، وبين علامتي اقتباس عبارة بترتيبها،
 * وكلمة تسبقها «-» تُستبعد.
 */
class QueryPlanner(private val corpusProvider: () -> SQLiteDatabase) {

    private val corpus get() = corpusProvider()

    fun plan(
        raw: String,
        engines: Set<Engine>,
        scope: SearchScope = SearchScope.MATN,
        bookFilter: Long? = null,
        hokmFilter: Int? = null
    ): SearchPlan {
        val trimmed = raw.trim()
        val isPhrase = trimmed.length > 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
        val cleaned = trimmed.trim('"')
        val terms = ArrayList<QueryTerm>()
        for (chunk in cleaned.split(Regex("\\s+"))) {
            if (chunk.isBlank()) continue
            val negated = chunk.startsWith("-") || chunk.startsWith("ليس:")
            val word = chunk.removePrefix("-").removePrefix("ليس:")
            val n = ArabicText.normalize(word)
            if (n.isEmpty() || (n.length <= 1) || (!negated && ArabicText.isStopWord(n))) continue
            val stem = ArabicText.lightStem(n)
            val roots = if (Engine.MORPH in engines || Engine.SEMANTIC in engines) lookupRoots(n) else emptyList()
            terms.add(QueryTerm(original = word, normalized = n, stem = stem, roots = roots, negated = negated))
        }
        return SearchPlan(cleaned, terms, isPhrase, engines, scope, bookFilter, hokmFilter)
    }

    /** المفاتيح الصرفية للكلمة كما وردت، وإن لم توجد فبعد تجريدها من «ال» ونحوها */
    fun lookupRoots(wordNorm: String): List<String> {
        val direct = queryMorph(wordNorm)
        if (direct.isNotEmpty()) return direct
        val stripped = ArabicText.stripArticle(wordNorm)
        if (stripped != wordNorm && stripped.length >= 2) {
            val viaStripped = queryMorph(stripped)
            if (viaStripped.isNotEmpty()) return viaStripped
        }
        return emptyList()
    }

    private fun queryMorph(word: String): List<String> = try {
        corpus.rawQuery("SELECT DISTINCT key FROM morph WHERE word_norm=? LIMIT 6", arrayOf(word)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    } catch (_: Exception) { emptyList() }

    fun ftsExpression(plan: SearchPlan, engine: Engine): String? = FtsExpression.build(plan, engine)
}

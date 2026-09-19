package org.murabbie.muhaddith.search

import org.murabbie.muhaddith.data.Engine
import org.murabbie.muhaddith.data.QueryTerm
import org.murabbie.muhaddith.data.SearchPlan
import org.murabbie.muhaddith.data.SearchScope

/**
 * بناء تعبير MATCH الخاص بـ FTS5 — منطق خالص بلا اعتماد على قاعدة البيانات.
 *
 * أعمدة الفهرس في قاعدة المحدِّث المدمجة: matn (لفظ المتن المطبَّع)، sanad (لفظ الإسناد
 * المطبَّع)، stems (المفاتيح الصرفية لكلمات المتن: الجذر، أو الوزن لغير المشتق).
 */
object FtsExpression {

    fun columnsFor(engine: Engine, scope: SearchScope): List<String> = when (engine) {
        Engine.MORPH, Engine.SEMANTIC -> when (scope) {
            SearchScope.MATN -> listOf("stems")
            SearchScope.ISNAD -> listOf("sanad")
            SearchScope.BOTH -> listOf("stems", "sanad")
        }
        Engine.LITERAL -> when (scope) {
            SearchScope.MATN -> listOf("matn")
            SearchScope.ISNAD -> listOf("sanad")
            SearchScope.BOTH -> listOf("matn", "sanad")
        }
    }

    private fun q(s: String) = ArabicText.ftsQuote(s)

    private fun group(alts: Collection<String>): String =
        if (alts.size == 1) q(alts.first()) else "(" + alts.joinToString(" OR ") { q(it) } + ")"

    /** بدائل الكلمة في عمود معيّن */
    private fun alternatives(t: QueryTerm, engine: Engine, column: String): Set<String> = when {
        engine == Engine.LITERAL || column != "stems" -> linkedSetOf(t.normalized)
        else -> linkedSetOf<String>().apply {
            if (t.roots.isNotEmpty()) addAll(t.roots) else add(t.normalized)
        }
    }

    fun build(plan: SearchPlan, engine: Engine): String? {
        val positive = plan.terms.filter { !it.negated }
        val negative = plan.terms.filter { it.negated }
        if (positive.isEmpty()) return null

        if (engine == Engine.SEMANTIC) {
            val expanded = positive.flatMap { it.synonymKeys }
            if (expanded.isEmpty()) return null
        }

        val parts = columnsFor(engine, plan.scope).map { col ->
            val body: String = when {
                engine == Engine.LITERAL && plan.phrase ->
                    q(positive.joinToString(" ") { it.normalized })

                engine == Engine.SEMANTIC && col == "stems" -> {
                    val alts = linkedSetOf<String>()
                    positive.forEach { t ->
                        alts.addAll(alternatives(t, Engine.MORPH, col))
                        alts.addAll(t.synonymKeys)
                    }
                    alts.joinToString(" OR ") { q(it) }
                }

                else -> positive.joinToString(" AND ") { t -> group(alternatives(t, engine, col)) }
            }
            val neg = negative.map { group(alternatives(it, engine, col)) }
            val withNot = if (neg.isEmpty()) body else "($body) NOT (${neg.joinToString(" OR ")})"
            "$col : ($withNot)"
        }
        return parts.joinToString(" OR ")
    }
}

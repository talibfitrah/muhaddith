package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.Engine
import org.murabbie.muhaddith.data.QueryTerm
import org.murabbie.muhaddith.data.SearchPlan
import org.murabbie.muhaddith.data.SearchScope
import org.murabbie.muhaddith.search.FtsExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsExpressionTest {

    private fun plan(
        terms: List<QueryTerm>, engines: Set<Engine> = setOf(Engine.LITERAL),
        phrase: Boolean = false, scope: SearchScope = SearchScope.MATN
    ) = SearchPlan("q", terms, phrase, engines, scope, null, null)

    private val niyya = QueryTerm("النية", "النيه", "نيه", roots = listOf("نوي"))
    private val amal = QueryTerm("الأعمال", "الاعمال", "اعمال", roots = listOf("عمل"))
    private val unknown = QueryTerm("قسطنطينية", "قسطنطينيه", "قسطنطيني", roots = emptyList())
    private val neg = QueryTerm("-رمضان", "رمضان", "رمضان", roots = listOf("رمض"), negated = true)

    @Test fun literalUsesMatnColumnAndAnd() {
        assertEquals("matn : (\"النيه\" AND \"الاعمال\")", FtsExpression.build(plan(listOf(niyya, amal)), Engine.LITERAL))
    }

    @Test fun literalPhraseKeepsWordOrder() {
        assertEquals("matn : (\"الاعمال النيه\")", FtsExpression.build(plan(listOf(amal, niyya), phrase = true), Engine.LITERAL))
    }

    @Test fun morphUsesStemsColumnWithRoots() {
        assertEquals("stems : (\"نوي\" AND \"عمل\")", FtsExpression.build(plan(listOf(niyya, amal)), Engine.MORPH))
    }

    @Test fun morphFallsBackToWordWhenNoRoot() {
        assertEquals("stems : (\"قسطنطينيه\")", FtsExpression.build(plan(listOf(unknown)), Engine.MORPH))
    }

    @Test fun morphWithSeveralRootsIsDisjunctive() {
        val amb = QueryTerm("علي", "علي", "علي", roots = listOf("علو", "علي"))
        assertEquals("stems : ((\"علو\" OR \"علي\"))", FtsExpression.build(plan(listOf(amb)), Engine.MORPH))
    }

    @Test fun negatedTermsUseNot() {
        val e = FtsExpression.build(plan(listOf(niyya, neg)), Engine.LITERAL)
        assertEquals("matn : ((\"النيه\") NOT (\"رمضان\"))", e)
    }

    @Test fun negatedOnMorphUsesRootOfNegatedTerm() {
        val e = FtsExpression.build(plan(listOf(niyya, neg)), Engine.MORPH)
        assertEquals("stems : ((\"نوي\") NOT (\"رمض\"))", e)
    }

    @Test fun onlyNegatedTermsYieldNothing() {
        assertNull(FtsExpression.build(plan(listOf(neg)), Engine.LITERAL))
    }

    @Test fun scopeBothSearchesMatnAndSanad() {
        val e = FtsExpression.build(plan(listOf(niyya), scope = SearchScope.BOTH), Engine.LITERAL)!!
        assertEquals("matn : (\"النيه\") OR sanad : (\"النيه\")", e)
    }

    @Test fun morphScopeIsnadFallsBackToLiteralOnSanad() {
        val e = FtsExpression.build(plan(listOf(niyya), scope = SearchScope.ISNAD), Engine.MORPH)!!
        assertEquals("sanad : (\"النيه\")", e)
    }

    @Test fun semanticWithoutSynonymKeysIsUnavailable() {
        assertNull(FtsExpression.build(plan(listOf(niyya)), Engine.SEMANTIC))
    }

    @Test fun emptyTermsYieldNoExpression() {
        assertNull(FtsExpression.build(plan(emptyList()), Engine.LITERAL))
    }

    @Test fun quotesInsideTermsAreEscaped() {
        val t = QueryTerm("a\"b", "a\"b", "a\"b", roots = emptyList())
        assertTrue(FtsExpression.build(plan(listOf(t)), Engine.LITERAL)!!.contains("\"a\"\"b\""))
    }
}

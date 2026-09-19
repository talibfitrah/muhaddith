package org.murabbie.muhaddith

import org.murabbie.muhaddith.search.PassageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageTextTest {
    private val text = ("قال أبو محمد: ونحن نقول إن الجمع بين الحديثين ممكن " + "كلمة ".repeat(80) +
        "وقد ذهب الشافعي إلى أن النسخ لا يثبت إلا بدليل " + "لفظ ".repeat(60)).trim()

    @Test fun shortTextReturnedWhole() {
        assertEquals("نص قصير", PassageText.snippet("نص قصير", listOf("قصير")))
    }

    @Test fun snippetCentersOnFirstHit() {
        val s = PassageText.snippet(text, listOf("النسخ"), 120)
        assertTrue(s, s.contains("النسخ"))
        assertTrue(s, s.startsWith("…"))
        assertTrue(s.length <= 130)
    }

    @Test fun articleInsensitive() {
        val s = PassageText.snippet(text, listOf("شافعي"), 100)
        assertTrue(s, s.contains("الشافعي"))
    }

    @Test fun noHitFallsBackToStart() {
        val s = PassageText.snippet(text, listOf("زيد"), 60)
        assertTrue(s, s.startsWith("قال أبو محمد"))
        assertTrue(s.endsWith("…"))
    }
}

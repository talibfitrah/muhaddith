package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.SharhPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SharhPointTest {
    private fun p(scholar: String, death: Int?, tabaka: Int? = null) =
        SharhPoint(1, 1, 1, 1, "كتاب", "مؤلف", scholar, death, tabaka, "فقهية", 0.5, "نص", false, 0.8, 1, 1)

    @Test fun layersFollowDeathAndTabaka() {
        assertEquals(0, p("ابن عباس", 68, 1).layer)
        assertEquals(0, p("أبو بكر", 13).layer)
        assertEquals(1, p("الحسن", 110).layer)
        assertEquals(1, p("مالك", 179).layer)
        assertEquals(2, p("الشافعي", 204).layer)
        assertEquals(2, p("ابن عبد البر", 463).layer)
        assertEquals(3, p("ابن حجر", 852).layer)
        assertEquals(4, p("ابن عثيمين", 1421).layer)
        assertEquals(5, p("مجهول", null).layer)
        assertEquals("الصحابة", SharhPoint.layerLabel(0))
        assertEquals("المعاصرون", SharhPoint.layerLabel(4))
    }

    @Test fun refUsesArabicDigits() {
        assertEquals("ج١ ص٥٢", p("x", 1).copy(vol = 1, page = 52).ref)
        assertEquals("", p("x", 1).copy(vol = null, page = null).ref)
    }
}

package org.murabbie.muhaddith

import org.murabbie.muhaddith.search.ArabicText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextTest {

    @Test fun removesDiacriticsAndUnifiesLetters() {
        assertEquals("انما الاعمال بالنيات",
            ArabicText.normalize("إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ"))
    }

    @Test fun unifiesAlifMaqsuraAndTaMarbuta() {
        assertEquals("علي نيه", ArabicText.normalize("على نية"))
    }

    @Test fun stripsTatweelAndPunctuation() {
        assertEquals("الرفق لا يكون في شيء الا زانه".replace("ء",""),
            ArabicText.normalize("الرِّفـــقُ لا يكونُ في شيءٍ إلا زانَه،"))
    }

    @Test fun lightStemStripsDefiniteArticle() {
        assertEquals("اعمال", ArabicText.lightStem("الأعمال"))
        assertEquals("نيات", ArabicText.lightStem("بالنيات"))
        assertEquals("هجرت", ArabicText.lightStem("هجرته"))
    }

    @Test fun lightStemKeepsShortWordsIntact() {
        assertEquals("نوي", ArabicText.lightStem("نوى"))
        assertEquals("علم", ArabicText.lightStem("علم"))
    }

    @Test fun contentTokensDropStopWords() {
        val t = ArabicText.contentTokens("قال رسول الله صلى الله عليه وسلم إنما الأعمال بالنيات")
        assertTrue(t.contains("الاعمال"))
        assertTrue(t.contains("بالنيات"))
        assertTrue(!t.contains("قال"))
        assertTrue(!t.contains("من"))
    }

    @Test fun arabicDigitsConvert() {
        assertEquals("١٤٠٠", ArabicText.arabicDigits(1400))
        assertEquals("ت ٢٥٦ هـ", ArabicText.arabicDigits("ت 256 هـ"))
    }

    @Test fun ftsQuoteEscapesQuotes() {
        assertEquals("\"نية\"", ArabicText.ftsQuote("نية"))
        assertEquals("\"ا\"\"ب\"", ArabicText.ftsQuote("ا\"ب"))
    }

    @Test fun stripArticleRemovesOnlyPrefixes() {
        assertEquals("نيه", ArabicText.stripArticle("النيه"))
        assertEquals("نيات", ArabicText.stripArticle("بالنيات"))
        assertEquals("اعمال", ArabicText.stripArticle("والاعمال"))
        assertEquals("علم", ArabicText.stripArticle("علم"))
        assertEquals("ال", ArabicText.stripArticle("ال"))
    }
}

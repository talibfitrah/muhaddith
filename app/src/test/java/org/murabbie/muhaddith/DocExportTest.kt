package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.Hadith
import org.murabbie.muhaddith.data.Ruling
import org.murabbie.muhaddith.data.RulingsBundle
import org.murabbie.muhaddith.export.DocExport
import org.murabbie.muhaddith.export.ExportItem
import org.murabbie.muhaddith.export.ExportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DocExportTest {
    private val h = Hadith(1, "146_1", 1, "صحيح البخاري", 1, 3, 319, 0, 2, "باب كيف كان بدء الوحي", "حَدَّثَنَا الحُمَيْدِيُّ", "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ")
    private val r = Ruling(1, "146_1", 319, "الترمذي", 279, "حسن صحيح", 0, "جامع الترمذي", "حديث 1569", "…قال أبو عيسى: هذا حديث حسن صحيح…", "text", null, null, 1.0)
    private val other = Ruling(2, null, 319, "الألباني", 1420, "صحيح", 0, "صحيح الجامع", "رقم 2", null, "albani", 100001L, "195_1", 0.72)

    @Test fun buildRespectsOptions() {
        val item = ExportItem(h, rulings = RulingsBundle(listOf(r), listOf(other)), waysCount = 5)
        val full = DocExport.build(listOf(item), ExportOptions(title = "ت", tashkeel = false, rulingQuotes = true), "بحث").map { it.text }
        assertTrue(full.any { it.contains("الاعمال") || it.contains("الأعمال") })
        assertTrue(full.any { it.contains("الترمذي") && it.contains("حسن صحيح") })
        assertTrue(full.any { it.contains("الألباني") && it.contains("ثقة الربط") })
        assertTrue(full.any { it.startsWith("حكم المحدِّث") })
        val minimal = DocExport.build(listOf(item), ExportOptions(title = "ت", sanad = false, rulings = false, muhaddithGrade = false, waysCount = false, header = false, chapter = false), null).map { it.text }
        assertTrue(minimal.none { it.contains("الترمذي") })
        assertTrue(minimal.none { it.contains("الحميدي") || it.contains("الحُمَيْدِيُّ") })
        assertTrue(minimal.none { it.startsWith("حكم المحدِّث") })
    }

    @Test fun docxIsValidZipWithText() {
        val paras = DocExport.build(listOf(ExportItem(h)), ExportOptions(title = "عنوان & اختبار"), null)
        val bytes = DocExport.docx(paras, ExportOptions(title = "عنوان & اختبار"))
        val names = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(names.containsKey("[Content_Types].xml")); assertTrue(names.containsKey("word/document.xml")); assertTrue(names.containsKey("word/styles.xml"))
        val doc = names.getValue("word/document.xml")
        assertTrue(doc.contains("بِالنِّيَّاتِ"))
        assertTrue(doc.contains("عنوان &amp; اختبار"))
        assertTrue(doc.contains("w:jc w:val=\"right\"") && doc.contains("<w:bidi/>") && doc.contains("Traditional Arabic"))
        assertTrue(!doc.contains("w:color"))
    }

    @Test fun fileNameIsSafe() {
        val n = DocExport.fileName("بحث: الصلاة/الزكاة?", "docx")
        assertTrue(n.endsWith(".docx")); assertTrue(!n.contains("/") && !n.contains("?") && !n.contains(":"))
        assertEquals("muhaddith", DocExport.fileName("   ", "pdf").substringBefore("-"))
    }
}

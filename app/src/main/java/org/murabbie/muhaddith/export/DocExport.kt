package org.murabbie.muhaddith.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import org.murabbie.muhaddith.data.*
import org.murabbie.muhaddith.search.ArabicText
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ما يُضمَّن في المستند المصدَّر */
data class ExportOptions(
    val title: String = "بحث من المحدِّث",
    val format: String = "docx",             // docx | pdf
    val header: Boolean = true,              // سطر التعريف: الاستعلام والتاريخ والمصدر
    val numbering: Boolean = true,
    val bookLine: Boolean = true,            // الكتاب ورقم الحديث والصفحة
    val chapter: Boolean = true,
    val sanad: Boolean = true,
    val matn: Boolean = true,
    val muhaddithGrade: Boolean = true,
    val sahabi: Boolean = true,
    val narrators: Boolean = false,
    val rulings: Boolean = true,             // أحكام العلماء
    val rulingQuotes: Boolean = false,       // نص الحكم كما ورد
    val rulingsOthers: Boolean = true,       // أحكام الطرق الأخرى (بحسب دقة الربط)
    val passages: Boolean = true,            // مواضع الذكر في كتب مختلف الحديث والتخريج والألباني
    val waysCount: Boolean = true,
    val tashkeel: Boolean = true,
    val fontSize: Int = 16,
    val story: Boolean = true,               // سبب الحديث وسياقه (روايات ثابتة + كتب أسباب الورود)
    val points: Boolean = true,              // شروح العلماء واستنباطاتهم
    val pointsByCategory: Boolean = true,    // تقسيم الاستنباطات بحسب الموضوع
    val pointCategories: Set<String> = emptySet(),   // فارغ = كل الأصناف
    val maxPoints: Int = 40
)

/** مادة حديث واحد جاهزة للتصدير */
data class ExportItem(
    val hadith: Hadith,
    val sahaba: List<Narrator> = emptyList(),
    val narrators: List<Narrator> = emptyList(),
    val rulings: RulingsBundle? = null,
    val passages: List<PassageRef> = emptyList(),
    val waysCount: Int = 0,
    val stories: List<Story> = emptyList(),
    val asbab: List<PassageRef> = emptyList(),
    val points: List<SharhPoint> = emptyList()
)

/** فقرة في المستند: نص ومستوى (٠ متن، ١ عنوان رئيس، ٢ عنوان حديث، ٣ عنوان فرعي، ٤ ملاحظة صغيرة) */
data class Para(val text: String, val level: Int = 0)

object DocExport {

    fun build(items: List<ExportItem>, o: ExportOptions, subtitle: String?): List<Para> {
        val out = ArrayList<Para>()
        fun t(s: String?) = if (s == null) "" else if (o.tashkeel) s else ArabicText.stripDiacritics(s)
        out.add(Para(o.title, 1))
        if (o.header) {
            val date = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date())
            out.add(Para(listOfNotNull(subtitle, "${ArabicText.arabicDigits(items.size)} حديثًا", "التاريخ ${ArabicText.arabicDigits(date)}", "المحدِّث").joinToString(" · "), 4))
        }
        items.forEachIndexed { i, it ->
            val h = it.hadith
            val head = buildString {
                if (o.numbering) append(ArabicText.arabicDigits(i + 1)).append(" — ")
                append(h.bookTitle)
                h.hadithNum?.let { n -> append(" (").append(ArabicText.arabicDigits(n)).append(")") }
            }
            out.add(Para(if (o.bookLine) head else if (o.numbering) ArabicText.arabicDigits(i + 1) else "", 2))
            if (o.bookLine) h.pageNum?.let { p -> out.add(Para("ص ${ArabicText.arabicDigits(p)}", 4)) }
            if (o.chapter && !h.chapter.isNullOrBlank()) out.add(Para(t(h.chapter), 3))
            if (o.sanad && !h.sanad.isNullOrBlank()) out.add(Para(t(h.sanad)))
            if (o.matn) out.add(Para(t(h.matn)))
            if (o.muhaddithGrade) h.hokmLabel?.let { g -> out.add(Para("حكم المحدِّث: $g", 4)) }
            if (o.sahabi && it.sahaba.isNotEmpty()) out.add(Para("الصحابي: " + it.sahaba.joinToString("، ") { it.shohra ?: it.name }, 4))
            if (o.narrators && it.narrators.isNotEmpty()) out.add(Para("رجال الإسناد: " + it.narrators.joinToString("، ") { n -> (n.shohra ?: n.name) + (n.wasfRotba?.let { " ($it)" } ?: "") }, 4))
            if (o.waysCount && it.waysCount > 1) out.add(Para("طرق الحديث في الحزمة: ${ArabicText.arabicDigits(it.waysCount)}", 4))
            val rb = it.rulings
            if (o.rulings && rb != null && !rb.isEmpty) {
                out.add(Para("أحكام العلماء", 3))
                fun line(r: Ruling, other: Boolean) = buildString {
                    append(r.scholar); r.death?.let { append(" (ت ").append(ArabicText.arabicDigits(it)).append(")") }
                    append(": ").append(t(r.grade))
                    listOfNotNull(r.source, r.ref?.takeIf { s -> s.isNotBlank() }?.let { ArabicText.arabicDigits(it) }).takeIf { l -> l.isNotEmpty() }?.let { append(" — ").append(it.joinToString("، ")) }
                    if (other) append(" [على طريق أخرى، ثقة الربط ").append(ArabicText.arabicDigits((r.conf * 100).toInt())).append("٪]")
                }
                rb.own.forEach { r -> out.add(Para("• " + line(r, false))); if (o.rulingQuotes && !r.quote.isNullOrBlank()) out.add(Para(t(r.quote), 4)) }
                if (o.rulingsOthers) rb.others.forEach { r -> out.add(Para("• " + line(r, true))); if (o.rulingQuotes && !r.quote.isNullOrBlank()) out.add(Para(t(r.quote), 4)) }
            }
            if (o.story && (it.stories.isNotEmpty() || it.asbab.isNotEmpty())) {
                out.add(Para("سبب الحديث وسياقه", 3))
                it.asbab.forEach { p -> out.add(Para("• في ${p.bookTitle} — ${p.author}" + listOfNotNull(p.vol?.let { v -> "ج${ArabicText.arabicDigits(v)}" }, p.page?.let { g -> "ص${ArabicText.arabicDigits(g)}" }).joinToString(" ", prefix = " ") + (p.snippet?.let { sn -> ": " + t(sn) } ?: ""))) }
                it.stories.forEach { st ->
                    out.add(Para("• رواية ${st.hadith.bookTitle}" + (st.hadith.hadithNum?.let { n -> " (${ArabicText.arabicDigits(n)})" } ?: "") + (st.hadith.hokmLabel?.let { g -> " — $g" } ?: "") + ":", 4))
                    out.add(Para(t(st.hadith.matn)))
                }
            }
            if (o.points && it.points.isNotEmpty()) {
                out.add(Para("شروح العلماء واستنباطاتهم من الحديث", 3))
                val pts = it.points.filter { p -> o.pointCategories.isEmpty() || p.category in o.pointCategories }.take(o.maxPoints)
                val refs = ArrayList<String>()
                fun cite(p: SharhPoint): String {
                    refs.add(SharhPoint.sourceLine(p))
                    return "(" + ArabicText.arabicDigits(refs.size) + ")"
                }
                fun pline(p: SharhPoint) = SharhPoint.headline(p) + " " + t(p.text) + " " + cite(p)
                if (o.pointsByCategory) {
                    pts.groupBy { p -> p.category }.toSortedMap(compareBy { c -> SharhPoint.CATEGORIES.indexOf(c) }).forEach { (cat, list) ->
                        out.add(Para("المسائل ال$cat", 4))
                        list.groupBy { p -> p.layer }.toSortedMap().forEach { (layer, l2) ->
                            out.add(Para(SharhPoint.layerLabel(layer) + ":", 4))
                            l2.forEach { p -> out.add(Para("• " + pline(p))) }
                        }
                    }
                } else {
                    pts.groupBy { p -> p.layer }.toSortedMap().forEach { (layer, l2) ->
                        out.add(Para(SharhPoint.layerLabel(layer) + ":", 4))
                        l2.forEach { p -> out.add(Para("• " + pline(p))) }
                    }
                }
                if (refs.isNotEmpty()) {
                    out.add(Para("المصادر:", 4))
                    refs.forEachIndexed { i, r -> out.add(Para("(" + ArabicText.arabicDigits(i + 1) + ") " + r, 4)) }
                }
            }
            if (o.passages && it.passages.isNotEmpty()) {
                out.add(Para("مواضع ذكر الحديث في كتب مختلف الحديث والتخريج", 3))
                it.passages.forEach { p ->
                    out.add(Para("• ${p.bookTitle} — ${p.author}" + listOfNotNull(p.vol?.let { "ج${ArabicText.arabicDigits(it)}" }, p.page?.let { "ص${ArabicText.arabicDigits(it)}" }).joinToString(" ", prefix = " ") +
                        (p.title?.takeIf { s -> s.isNotBlank() }?.let { " (${t(it)})" } ?: "")))
                }
            }
        }
        return out
    }

    // ---------------- DOCX ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** يبني ملف Word (OOXML) يدويًّا: خط Traditional Arabic، اتجاه يمين، محاذاة يمين، تباعد ٢٤٠، بلا ألوان */
    fun docx(paras: List<Para>, o: ExportOptions): ByteArray {
        val body = StringBuilder()
        val base = o.fontSize
        for (p in paras) {
            if (p.text.isBlank()) continue
            val (size, bold) = when (p.level) { 1 -> (base + 8) to true; 2 -> (base + 4) to true; 3 -> (base + 2) to true; 4 -> (base - 4).coerceAtLeast(10) to false; else -> base to false }
            val hp = size * 2
            body.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"right\"/><w:spacing w:line=\"240\" w:lineRule=\"auto\" w:after=\"120\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Traditional Arabic\" w:hAnsi=\"Traditional Arabic\" w:cs=\"Traditional Arabic\"/>")
            if (bold) body.append("<w:b/><w:bCs/>")
            body.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/><w:rtl/></w:rPr><w:t xml:space=\"preserve\">").append(esc(p.text)).append("</w:t></w:r></w:p>")
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/><w:bidi/></w:sectPr>" +
            "</w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Traditional Arabic\" w:hAnsi=\"Traditional Arabic\" w:cs=\"Traditional Arabic\"/><w:sz w:val=\"${o.fontSize * 2}\"/><w:szCs w:val=\"${o.fontSize * 2}\"/><w:lang w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:bidi/><w:jc w:val=\"right\"/><w:spacing w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:pPr><w:bidi/><w:jc w:val=\"right\"/></w:pPr></w:style></w:styles>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        val docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        val core = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(o.title)}</dc:title><dc:creator>المحدِّث</dc:creator></cp:coreProperties>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", contentTypes); put("_rels/.rels", rels); put("word/_rels/document.xml.rels", docRels)
            put("word/document.xml", document); put("word/styles.xml", styles); put("docProps/core.xml", core)
        }
        return bos.toByteArray()
    }

    // ---------------- PDF ----------------

    /** يرسم الفقرات على صفحات A4 بخط النظام (يدعم العربية والتشكيل) مع اتجاه يمين */
    fun pdf(ctx: Context, paras: List<Para>, o: ExportOptions): ByteArray {
        val pageW = 595; val pageH = 842; val margin = 48
        val width = pageW - 2 * margin
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = margin
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF000000.toInt(); typeface = serif }
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            page = p; canvas = p.canvas; y = margin
            val fl = StaticLayout.Builder.obtain("المحدِّث — ${ArabicText.arabicDigits(pageNo)}", 0, 0, footer, width)
                .setTextDirection(TextDirectionHeuristics.RTL).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            p.canvas.save(); p.canvas.translate(margin.toFloat(), (pageH - margin + 10).toFloat()); fl.draw(p.canvas); p.canvas.restore()
        }
        newPage()
        for (p in paras) {
            if (p.text.isBlank()) continue
            val (size, bold) = when (p.level) { 1 -> (o.fontSize + 8) to true; 2 -> (o.fontSize + 4) to true; 3 -> (o.fontSize + 2) to true; 4 -> (o.fontSize - 4).coerceAtLeast(9) to false; else -> o.fontSize to false }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size.toFloat(); color = 0xFF000000.toInt()
                typeface = Typeface.create(serif, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(p.text, 0, p.text.length, paint, width)
                .setTextDirection(TextDirectionHeuristics.RTL).setAlignment(if (p.level == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f).setIncludePad(true).build()
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                // كم سطرًا يسع في بقية الصفحة؟
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= pageH - margin - y) last++
                if (layout.getLineBottom(last) - top > pageH - margin - y) {
                    if (y > margin) { newPage(); continue } else last = line
                }
                val bottom = layout.getLineBottom(last)
                val c = canvas!!
                c.save()
                c.translate(margin.toFloat(), (y - top).toFloat())
                c.clipRect(0, top, width, bottom)
                layout.draw(c)
                c.restore()
                y += bottom - top
                line = last + 1
            }
            y += (size * 0.5f).toInt()
        }
        page?.let { doc.finishPage(it) }
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos); doc.close()
        return bos.toByteArray()
    }

    /** اسم ملف آمن من العنوان */
    fun fileName(title: String, ext: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "muhaddith" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return "$safe-$stamp.$ext"
    }
}

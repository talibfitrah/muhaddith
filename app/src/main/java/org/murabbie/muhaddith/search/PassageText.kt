package org.murabbie.muhaddith.search

/** أدوات نصوص مقاطع كتب مختلف الحديث (مقتطفات البحث) — منطق خالص قابل للاختبار */
object PassageText {
    /** مقتطف حول أول ورود لأحد ألفاظ البحث (بمقارنة مطبَّعة)، وإلا أول النص */
    fun snippet(text: String, toks: List<String>, len: Int = 240): String {
        val plain = ArabicText.stripDiacritics(text).replace('\n', ' ')
        if (plain.length <= len) return plain
        val words = plain.split(' ')
        val normToks = toks.map { ArabicText.stripArticle(ArabicText.normalize(it)) }.filter { it.length > 1 }
        var hitWord = -1
        if (normToks.isNotEmpty()) {
            for ((i, w) in words.withIndex()) {
                val n = ArabicText.normalize(w); val st = ArabicText.stripArticle(n)
                if (normToks.any { t -> n == t || st == t || (t.length >= 3 && (n.startsWith(t) || st.startsWith(t))) }) { hitWord = i; break }
            }
        }
        if (hitWord < 0) return plain.take(len).trimEnd() + "…"
        // نبدأ قبل الكلمة المصابة بنحو ثلث النافذة
        var start = 0; var acc = 0
        val before = ArrayList<Int>()
        for (i in 0 until hitWord) before.add(words[i].length + 1)
        var back = 0
        for (i in before.indices.reversed()) { if (back + before[i] > len / 3) { start = i + 1; break }; back += before[i] }
        val sb = StringBuilder()
        var i = start
        while (i < words.size && sb.length + words[i].length + 1 <= len) { if (sb.isNotEmpty()) sb.append(' '); sb.append(words[i]); i++ }
        return (if (start > 0) "…" else "") + sb.toString() + (if (i < words.size) "…" else "")
    }
}

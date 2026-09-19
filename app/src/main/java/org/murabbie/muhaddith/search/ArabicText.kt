package org.murabbie.muhaddith.search

/**
 * تطبيع النص العربي: يجب أن يطابق تمامًا دالة norm() في tools/build_sample_db.py
 * لأن الفهرس مبنيّ على النص المطبَّع.
 */
object ArabicText {

    private val DIACRITICS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val NON_WORD = Regex("[^\\u0621-\\u064A0-9a-zA-Z\\s]")
    private val SPACES = Regex("\\s+")

    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var s = input
        s = DIACRITICS.replace(s, "")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                'ء' -> {}
                else -> sb.append(ch)
            }
        }
        s = NON_WORD.replace(sb.toString(), " ")
        return SPACES.replace(s, " ").trim()
    }

    /** إزالة التشكيل فقط للعرض (يبقي الهمزات والتاء المربوطة كما هي) */
    fun stripDiacritics(input: String?): String = if (input.isNullOrEmpty()) "" else DIACRITICS.replace(input, "")

    fun tokens(input: String?): List<String> {
        val n = normalize(input)
        if (n.isEmpty()) return emptyList()
        return n.split(' ').filter { it.isNotBlank() }
    }

    /** كلمات وظيفية لا تُفيد في البحث */
    private val STOP = setOf(
        "من", "في", "علي", "الي", "عن", "ان", "انه", "ما", "لا", "هو", "هي",
        "قال", "قالت", "حدثنا", "حدثني", "اخبرنا", "عند", "الذي", "التي",
        "وقال", "ثم", "او", "و", "ف", "ب", "ل", "كان", "كانت", "هذا", "هذه",
        "به", "له", "لم", "قد", "كل", "بن", "ابن", "رضي", "الله", "عنه", "عنها"
    )

    fun isStopWord(w: String) = w in STOP

    fun contentTokens(input: String?): List<String> =
        tokens(input).filter { it.length > 1 && !isStopWord(it) }

    /**
     * تجريد صرفي خفيف (light stemming) للعربية: يُستعمل احتياطًا
     * حين لا يوجد الجذر في معجم الجذور.
     */
    private val PREFIXES = listOf("والل", "وال", "بال", "كال", "فال", "الل", "ال", "لل", "و")
    private val SUFFIXES = listOf("هما", "كما", "هم", "هن", "نا", "كم", "كن", "ها", "ان", "ات", "ون", "ين", "يه", "ه", "ي")

    fun lightStem(word: String): String {
        var w = normalize(word)
        if (w.length <= 3) return w
        for (p in PREFIXES) {
            if (w.length - p.length >= 3 && w.startsWith(p)) { w = w.substring(p.length); break }
        }
        for (s in SUFFIXES) {
            if (w.length - s.length >= 3 && w.endsWith(s)) { w = w.dropLast(s.length); break }
        }
        return w
    }

    private val ARTICLES = listOf("وبال", "وال", "بال", "كال", "فال", "ولل", "ال", "لل")

    /** يجرّد أداة التعريف وما يسبقها من حروف الجر والعطف فقط (بلا مساس بالجذع) */
    fun stripArticle(word: String): String {
        for (p in ARTICLES) {
            if (word.length - p.length >= 2 && word.startsWith(p)) return word.substring(p.length)
        }
        if (word.length >= 4 && (word.startsWith("و") || word.startsWith("ف"))) return word.substring(1)
        return word
    }

    /** بادئة آمنة لاستعمالها في FTS5 مع مِحرف * */
    fun stemPrefix(word: String): String {
        val st = lightStem(word)
        return if (st.length >= 3) st else normalize(word)
    }

    private val AR_DIGITS = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')

    /** تحويل الأرقام إلى الأرقام العربية-الهندية */
    fun arabicDigits(value: Any?): String {
        val s = value?.toString() ?: return ""
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') AR_DIGITS[c - '0'] else c)
        return sb.toString()
    }

    /** تهريب نصّ ليُستعمل داخل استعلام FTS5 كعبارة */
    fun ftsQuote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""
}

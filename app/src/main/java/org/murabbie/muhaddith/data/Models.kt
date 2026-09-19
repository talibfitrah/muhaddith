package org.murabbie.muhaddith.data

/** محرك البحث الذي أنتج النتيجة */
enum class Engine(val label: String) {
    LITERAL("نصي"), MORPH("صرفي"), SEMANTIC("دلالي")
}

enum class SearchScope(val label: String) {
    MATN("المتن"), ISNAD("الإسناد"), BOTH("المتن والإسناد")
}

/** أحكام الأحاديث كما في قاعدة المحدِّث */
object Hokm {
    private val labels = mapOf(
        0 to "صحيح", 1 to "حسن", 2 to "ضعيف", 3 to "شديد الضعف", 4 to "متهم", 5 to "موضوع"
    )
    fun label(code: Int?): String? = code?.let { labels[it] }
    val filterable = listOf(0 to "صحيح", 1 to "حسن", 2 to "ضعيف")
}

data class Book(
    val id: Long, val title: String, val author: String?,
    val deathYear: String?, val priority: Int, val hadithCount: Int
)

data class Narrator(
    val id: Long, val name: String, val shohra: String?, val konya: String?,
    val lakab: String?, val rotba: Int?, val wasfRotba: String?, val tabaka: Int?,
    val deathYear: String?, val baladWafa: String?, val bukhari: Boolean, val muslim: Boolean,
    val marweyaat: Int?
)

data class Cluster(
    val id: Long, val taraf: String?, val hokmType: Int?, val sahabaCount: Int?, val mokararat: Int?
)

data class ClusterSahabi(val rawi: Narrator, val wayCount: Int?, val taraf: String?)

data class Hadith(
    val id: Long,
    val bhid: String,
    val bookId: Long,
    val bookTitle: String,
    val hadithNum: Int?,
    val pageNum: Int?,
    val clusterId: Long?,
    val hokm: Int?,
    val blockType: Int?,
    val chapter: String?,
    val sanad: String?,
    val matn: String
) {
    val hokmLabel: String? get() = Hokm.label(hokm)
}

data class SearchHit(
    val hadith: Hadith,
    val score: Double,
    val engines: Set<Engine>,
    val snippet: String,
    /** ملخّص أحكام العلماء (من حزمة الأحكام إن ثُبّتت): «صحّحه ٣ · ضعّفه ١» */
    val rulingSummary: String? = null
)

data class QueryTerm(
    val original: String,
    val normalized: String,
    val stem: String,
    /** المفاتيح الصرفية (جذر أو وزن) من معجم المحدِّث */
    val roots: List<String>,
    val synonyms: List<String> = emptyList(),
    val synonymKeys: List<String> = emptyList(),
    /** مصطلح مستبعد (سبقته علامة -) */
    val negated: Boolean = false
) {
    val root: String? get() = roots.firstOrNull()
}

data class SearchPlan(
    val raw: String,
    val terms: List<QueryTerm>,
    val phrase: Boolean,
    val engines: Set<Engine>,
    val scope: SearchScope,
    val bookFilter: Long?,
    val hokmFilter: Int?,
    /** نطاق الكتب المفعّل من الإعدادات (فارغ = كل الكتب) */
    val bookScope: Set<Long> = emptySet()
)

data class SavedCollection(val id: Long, val name: String, val itemCount: Int)

/** حالة حزمة البيانات المستعملة الآن */
data class DatasetInfo(
    val name: String,
    val hadiths: Int,
    val books: Int,
    val rawis: Int,
    val isFull: Boolean,
    val sizeBytes: Long,
    val isEmpty: Boolean = false,
    val isImported: Boolean = false,
    val location: String = "الذاكرة الداخلية"
)

// ---------- حزمة الأحكام ومختلف الحديث ----------

/** عالم له أحكام في الحزمة */
data class Scholar(val id: Long, val name: String, val death: Int?, val kind: String, val rulings: Int)

/**
 * حكم عالم على حديث. [bhid] إن كان الحكم على هذه الرواية بعينها، و[clusterId] للحديث المجمَّع.
 * [origin]: text = من نص الكتاب نفسه في المتن · api = من طبعة محقَّقة · bulugh = بلوغ المرام · passage = من كتب التخريج والعلل
 */
data class Ruling(
    val id: Long, val bhid: String?, val clusterId: Long?, val scholar: String, val death: Int?,
    val grade: String, val level: Int?, val source: String?, val ref: String?, val quote: String?,
    val origin: String, val sectionId: Long?, val viaBhid: String?,
    /** ثقة ربط الحكم بهذا الحديث: ١ = على الرواية بعينها؛ أقل = رُبط بمطابقة نصية مع طريق أخرى */
    val conf: Double = 1.0
) {
    val levelLabel: String? get() = Hokm.label(level)
    val confLabel: String get() = when {
        bhid != null && conf >= 0.99 -> "على هذه الرواية بعينها"
        conf >= 0.85 -> "مطابقة نصية قوية"
        conf >= 0.65 -> "مطابقة نصية متوسطة"
        else -> "مطابقة نصية ضعيفة — تثبّت"
    }
}

/** درجات دقة الربط في الإعدادات */
object RulingPrecision {
    const val STRICT = 0; const val PRECISE = 1; const val WIDE = 2
    fun label(p: Int) = when (p) { STRICT -> "دقيق جدًّا"; WIDE -> "واسع"; else -> "دقيق" }
    fun minConf(p: Int) = when (p) { STRICT -> 0.85; WIDE -> 0.0; else -> 0.65 }
    fun description(p: Int) = when (p) {
        STRICT -> "لا يُعرض إلا ما نُصّ عليه على هذه الرواية بعينها، أو ما رُبط بمطابقة نصية قوية جدًّا؛ والشروح على متن هذا الحديث بعينه لا على عنقوده."
        WIDE -> "يُعرض كل ما رُبط بالحديث المجمَّع ولو كانت المطابقة ضعيفة، والشروح على العنقود كله (قد يظهر حديث آخر مشابه)."
        else -> "يُعرض ما على الرواية نفسها، وما رُبط بطرقه بمطابقة نصية قوية أو متوسطة؛ والشروح على متن هذا الحديث وطرقه المتقاربة لا على العنقود كله."
    }
}

/** أحكام حديث: ما قيل في هذه الرواية بعينها، وما قيل في طرقه الأخرى (العنقود نفسه) */
data class RulingsBundle(val own: List<Ruling>, val others: List<Ruling>) {
    val isEmpty: Boolean get() = own.isEmpty() && others.isEmpty()
    val total: Int get() = own.size + others.size
}

/** كتاب من كتب مختلف الحديث والتخريج… kind: mukhtalif · nasikh · usul · takhrij · ilal · atraf */
data class MkBook(
    val id: Long, val title: String, val author: String, val death: Int?, val kind: String,
    val edition: String?, val editor: String?, val publisher: String?, val vols: Int, val sections: Int, val words: Int
) {
    val kindLabel: String get() = MkKinds.label(kind)
}

object MkKinds {
    val ORDER = listOf("asbab", "sharh", "modern", "albani", "mukhtalif", "nasikh", "usul", "takhrij", "ilal", "atraf")
    fun label(k: String) = when (k) {
        "mukhtalif" -> "مختلف الحديث ومشكله"
        "nasikh" -> "الناسخ والمنسوخ"
        "usul" -> "أصول الجمع بين الأحاديث (علوم الحديث)"
        "takhrij" -> "كتب التخريج"
        "ilal" -> "العلل والموضوعات"
        "atraf" -> "الأطراف"
        "albani" -> "كتب الألباني"
        "sharh" -> "شروح الحديث"
        "modern" -> "الشروح المعاصرة"
        "asbab" -> "أسباب ورود الحديث"
        else -> k
    }
}

data class MkSection(
    val id: Long, val bookId: Long, val ord: Int, val title: String?, val vol: Int?,
    val pageStart: Int?, val pageEnd: Int?, val words: Int, val text: String
) {
    val ref: String get() = listOfNotNull(vol?.let { "ج${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" },
        pageStart?.let { "ص${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" }).joinToString(" ")
}

/** موضع ذُكر فيه الحديث في كتب مختلف الحديث والتخريج */
/** موضع ورود نقطة (كتاب وصفحة) */
data class PointSource(val bookId: Long, val bookTitle: String, val author: String, val vol: Int?, val page: Int?, val sectionId: Long) {
    val ref: String get() = listOfNotNull(vol?.let { "ج${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" }, page?.let { "ص${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" }).joinToString(" ")
}

data class PassageRef(
    val sectionId: Long, val bookId: Long, val bookTitle: String, val author: String, val kind: String,
    val title: String?, val vol: Int?, val page: Int?, val hits: Int, val snippet: String?, val conf: Double = 0.7,
    /** وحدة شرح مخصّصة لهذا الحديث (لا مجرّد ذكر عابر له في شرح حديث آخر) */
    val primary: Boolean = true,
    /** وفاة المؤلف (لترتيب الشرّاح) */
    val death: Int? = null
)

data class PassageHit(val section: MkSection, val bookTitle: String, val author: String, val snippet: String)

data class AhkamInfo(
    val installed: Boolean, val rulings: Int = 0, val scholars: Int = 0, val sections: Int = 0,
    val books: Int = 0, val hadiths: Int = 0, val clusters: Int = 0, val sizeBytes: Long = 0, val built: String? = null,
    val albani: Int = 0, val outdated: Boolean = false
)

// ---------- حزمة الشروح وأسباب الورود ----------

data class ShuruhInfo(
    val installed: Boolean, val books: Int = 0, val sections: Int = 0, val points: Int = 0, val stories: Int = 0,
    val clusters: Int = 0, val scholars: Int = 0, val sizeBytes: Long = 0, val built: String? = null,
    /** عدد آثار الصحابة والتابعين بصيغة الرواية */
    val athar: Int = 0,
    /** حزمة موجودة لكنها من إصدار أقدم من المطلوب (بلا عمود kind) */
    val outdated: Boolean = false
)

/** استنباط أو قول عالم على حديث، من مقطع شرح معيَّن */
data class SharhPoint(
    val id: Long, val clusterId: Long, val sectionId: Long, val bookId: Long, val bookTitle: String, val author: String,
    val scholar: String, val death: Int?, val tabaka: Int?, val category: String, val catConf: Double,
    val text: String, val isAuthor: Boolean, val conf: Double, val vol: Int?, val page: Int?,
    /** qawl = قول منسوب («قال فلان»)، athar = أثر بصيغة الرواية («عن فلان أنه كان…») */
    val kind: String = "qawl",
    /** مصادر أخرى للأثر نفسه (دُمج تكراره من كتب عدة في نقطة واحدة) */
    val sources: List<PointSource> = emptyList()
) {
    val isAthar: Boolean get() = kind == "athar"
    /** شرح لفظ من ألفاظ المتن: «قوله «…»: …» */
    val isLemma: Boolean get() = kind == "lemma"
    /** طبقة القائل للترتيب: الصحابة ← التابعون وأتباعهم ← الأئمة ← المتأخرون ← المعاصرون */
    val layer: Int get() = when {
        tabaka == 1 || (death != null && death <= 100 && (tabaka == null || tabaka <= 1)) -> 0
        death != null && death <= 180 -> 1
        death != null && death <= 500 -> 2
        death != null && death <= 1300 -> 3
        death != null -> 4
        else -> 5
    }
    companion object {
        fun layerLabel(l: Int) = when (l) { 0 -> "الصحابة"; 1 -> "التابعون وأتباعهم"; 2 -> "الأئمة المتقدمون"; 3 -> "العلماء المتأخرون"; 4 -> "المعاصرون"; else -> "غير محدَّد الوفاة" }
        private fun ar(n: Int) = org.murabbie.muhaddith.search.ArabicText.arabicDigits(n)
        /** «قال الإمام فلان (ت … هـ):» أو للأثر «عن فلان رضي الله عنه أنه …» */
        fun headline(p: SharhPoint): String {
            val d = p.death?.let { " (ت ${ar(it)} هـ)" } ?: ""
            if (p.isAthar) return "عن " + p.scholar + (if (p.layer == 0) " رضي الله عنه" else "") + d + " أنه"
            val imam = p.layer >= 2 && !p.scholar.startsWith("ابن") && !p.scholar.startsWith("أبو")
            return "قال " + (if (imam) "الإمام " else "") + p.scholar + d + ":"
        }
        /** سطر المصدر المرقَّم: الكتاب — المؤلف، ج ص (نقله فلان) */
        fun sourceLine(p: SharhPoint): String =
            p.bookTitle + " — " + p.author + (p.ref.takeIf { it.isNotBlank() }?.let { "، $it" } ?: "") + (if (!p.isAuthor && !p.isAthar) " (نقله ${p.author})" else "") + (if (p.isAthar) " (أثر بإسناده)" else "")
        val CATEGORIES = listOf("عقدية", "فقهية", "اجتماعية وأخلاقية", "تربوية وسلوكية", "لغوية وبيانية", "حديثية (إسناد ورواية)", "أخرى")
    }
    val ref: String get() = listOfNotNull(vol?.let { "ج${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" }, page?.let { "ص${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)}" }).joinToString(" ")
}

/** رواية في المتن تحمل سياق الحديث وقصته */
data class Story(val hadith: Hadith, val score: Int)

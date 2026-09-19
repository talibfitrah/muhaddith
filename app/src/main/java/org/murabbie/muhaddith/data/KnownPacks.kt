package org.murabbie.muhaddith.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** حزمة معروفة: عدد أجزائها وبصمتها ثابتة في التطبيق ليتحقّق منها عند الاستيراد من الهاتف */
data class KnownPack(
    val id: String, val name: String, val file: String, val partCount: Int,
    val sizeGz: Long, val sizeDb: Long, val sha256: String, val hadiths: Int, val books: Int,
    /** corpus = قاعدة المتون · model = نموذج التضمين · vectors = متجهات المتون */
    val kind: String = "corpus",
    val description: String = ""
) {
    fun partName(i: Int) = "%s.%03d".format(file, i)
}

/** ما وُجد في مجلد على الهاتف من أجزاء حزمة معروفة */
data class FoundPack(val pack: KnownPack, val parts: Map<Int, Uri>) {
    val missing: List<Int> get() = (1..pack.partCount).filter { it !in parts }
    val complete: Boolean get() = missing.isEmpty()
    fun orderedUris(): List<Uri> = (1..pack.partCount).map { parts.getValue(it) }
}

object KnownPacks {
    val ALL = listOf(
        KnownPack(
            id = "corpus_tisa", name = "الكتب التسعة", file = "muhaddith_corpus_tisa.db.gz", partCount = 3,
            sizeGz = 66_192_276, sizeDb = 135_118_848,
            sha256 = "2dc6cd1c4b21f90fd51f44d163e93e13d170e614bb81b7dd7c49dde0f0f9ad35",
            hadiths = 58_684, books = 9
        ),
        KnownPack(
            id = "corpus_full", name = "الحزمة الكاملة", file = "muhaddith_corpus_full.db.gz", partCount = 20,
            sizeGz = 604_156_291, sizeDb = 922_292_224,
            sha256 = "778e9fd07070672daae4189a96713fa172760e1e34c099a1ff38bd701fb3d42f",
            hadiths = 463_733, books = 1400
        ),
        KnownPack(
            id = "ahkam", name = "أحكام العلماء ومختلف الحديث", file = "muhaddith_ahkam.db.gz", partCount = AHKAM_PARTS,
            sizeGz = AHKAM_GZ, sizeDb = AHKAM_RAW, sha256 = AHKAM_SHA, hadiths = 0, books = 0, kind = "ahkam",
            description = "أحكام الأئمة والمحققين على الأحاديث مع مصادرها، وكتب مختلف الحديث والناسخ والمنسوخ والتخريج كاملةً مربوطةً بالأحاديث"
        ),
        // الحزمة الحديثة (مخطط ٣: وحدات الشرح والمجموعات الدقيقة) — الاسم shuruh3 كي لا تنزّلها الإصدارات القديمة التي لا تعرف مخططها
        KnownPack(
            id = "shuruh3", name = "شروح الحديث وأسباب وروده وآثار الصحابة", file = "muhaddith_shuruh3.db.gz", partCount = SHURUH3_PARTS,
            sizeGz = SHURUH3_GZ, sizeDb = SHURUH3_RAW, sha256 = SHURUH3_SHA, hadiths = 0, books = 0, kind = "shuruh",
            description = "كتب الشروح من الخطابي وابن بطال وابن عبد البر إلى ابن حجر والشوكاني وابن عثيمين، والمصنفات وكتب الآثار، مع استنباطات العلماء وآثار الصحابة والتابعين مصنَّفةً ومرتَّبةً بالوفاة، مضبوطةً على متن الحديث"
        ),
        KnownPack(
            id = "shuruh", name = "شروح الحديث وأسباب وروده وآثار الصحابة (الإصدار السابق)", file = "muhaddith_shuruh.db.gz", partCount = SHURUH_PARTS,
            sizeGz = SHURUH_GZ, sizeDb = SHURUH_RAW, sha256 = SHURUH_SHA, hadiths = 0, books = 0, kind = "shuruh",
            description = "كتب الشروح من الخطابي وابن بطال وابن عبد البر إلى ابن حجر والشوكاني وابن عثيمين، وأسباب الورود، مع استنباطات العلماء مصنَّفةً ومرتَّبةً بالوفاة وسياق الحديث من الروايات الثابتة"
        ),
        // ---- البحث الدلالي (تُملأ الأحجام والبصمات من tools/semantic_packs.json) ----
        KnownPack(
            id = "model_bge", name = "نموذج البحث الدلالي (BGE-M3)", file = "muhaddith_model_bge.bin.gz", partCount = MODEL_PARTS,
            sizeGz = MODEL_GZ, sizeDb = MODEL_RAW, sha256 = MODEL_SHA, hadiths = 0, books = 0, kind = "model",
            description = "النموذج نفسه المستعمل في نسخة سطح المكتب، مكمَّمًا للهاتف (≈ ٣٤٥ م.ب)"
        ),
        KnownPack(
            id = "vectors_tisa", name = "متجهات الكتب التسعة", file = "muhaddith_vectors_tisa.bin.gz", partCount = VT_PARTS,
            sizeGz = VT_GZ, sizeDb = VT_RAW, sha256 = VT_SHA, hadiths = 58_684, books = 9, kind = "vectors",
            description = "تُستعمل مع حزمة الكتب التسعة"
        ),
        KnownPack(
            id = "vectors_full", name = "متجهات الحزمة الكاملة", file = "muhaddith_vectors_full.bin.gz", partCount = VF_PARTS,
            sizeGz = VF_GZ, sizeDb = VF_RAW, sha256 = VF_SHA, hadiths = 463_733, books = 1400, kind = "vectors",
            description = "تُستعمل مع الحزمة الكاملة"
        )
    )

    // @@AHKAM_CONSTANTS@@
    const val AHKAM_PARTS = 3; const val AHKAM_GZ = 91120864L; const val AHKAM_RAW = 164999168L; const val AHKAM_SHA = "4f2f010c28de027c2ef51623597d4fdf3a180b1a03010ee8e0bfe0572234e748"
    // @@END_AHKAM_CONSTANTS@@
    // @@SHURUH_CONSTANTS@@
    const val SHURUH_PARTS = 16; const val SHURUH_GZ = 469028390L; const val SHURUH_RAW = 688656384L; const val SHURUH_SHA = "526087ab57376485ea6f8f18ad93e35e1c6ae5e2d474159ebb007f43b66a3ea4"
    // @@END_SHURUH_CONSTANTS@@
    // @@SHURUH3_CONSTANTS@@
    const val SHURUH3_PARTS = 17; const val SHURUH3_GZ = 493366065L; const val SHURUH3_RAW = 775090176L; const val SHURUH3_SHA = "52660c978926378804d90fc88773782d0524954bd2c8121a8d7a49e4e9bd6a17"
    // @@END_SHURUH3_CONSTANTS@@
    // @@SEMANTIC_CONSTANTS@@
    const val MODEL_PARTS = 8; const val MODEL_GZ = 236725409L; const val MODEL_RAW = 344973261L; const val MODEL_SHA = "62d13cfc55f43389095ddf8194de2a54ca2daef7a6724d41a3b8e8313b2b7123"
    const val VT_PARTS = 2; const val VT_GZ = 33119314L; const val VT_RAW = 60561920L; const val VT_SHA = "7ad75ab0bacea9e5b4b45561366eba455c942455c49d5099b5c5ced916005e9e"
    const val VF_PARTS = 9; const val VF_GZ = 266819584L; const val VF_RAW = 478572488L; const val VF_SHA = "647221d79a472ca8fb0f7fde02a60bfc8202a692569882e2a8a915685ac0cfbd"
    // @@END_SEMANTIC_CONSTANTS@@

    /** يتعرّف على اسم جزء ولو أضاف المتصفح لاحقة مثل « (1)»: يعيد (الحزمة، رقم الجزء) */
    private val PART_RE = Regex("""muhaddith_(corpus_tisa|corpus_full|ahkam|shuruh3|shuruh|model_bge|vectors_tisa|vectors_full)\.(?:db|bin)\.gz\.(\d{3})""")

    fun identify(displayName: String): Pair<KnownPack, Int>? {
        val m = PART_RE.find(displayName) ?: return null
        val pack = ALL.firstOrNull { it.id == m.groupValues[1] } ?: return null
        val n = m.groupValues[2].toInt()
        if (n !in 1..pack.partCount) return null
        return pack to n
    }

    /** يمسح مجلدًا اختاره المستخدم (شجرة SAF) ويجمع أجزاء الحزم المعروفة */
    fun scanTree(ctx: Context, tree: Uri): List<FoundPack> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val found = HashMap<String, HashMap<Int, Uri>>()
        for (f in root.listFiles()) {
            if (!f.isFile) continue
            val (pack, n) = identify(f.name ?: continue) ?: continue
            // إن تكرّر الجزء (نسخة ثانية) خذ الأكبر حجمًا — الأقرب للاكتمال
            val map = found.getOrPut(pack.id) { HashMap() }
            val prev = map[n]
            if (prev == null || (DocumentFile.fromSingleUri(ctx, prev)?.length() ?: 0L) < f.length()) map[n] = f.uri
        }
        return ALL.mapNotNull { p -> found[p.id]?.let { FoundPack(p, it) } }
    }

    /** المجلدات العامة التي تُفحص عند منح «الوصول إلى كل الملفات» */
    fun publicFolders(): List<java.io.File> {
        val ext = android.os.Environment.getExternalStorageDirectory()
        val names = listOf("Download", "Downloads", "Documents", "Telegram", "Telegram/Telegram Documents", "WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents", "")
        return names.map { java.io.File(ext, it) }.filter { it.isDirectory }
    }

    /** فحص مجلدات الهاتف العامة مباشرة (يتطلب صلاحية الوصول إلى كل الملفات على أندرويد ١١+) */
    fun scanPublicFolders(): List<FoundPack> {
        val found = HashMap<String, HashMap<Int, java.io.File>>()
        for (dir in publicFolders()) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val (pack, n) = identify(f.name) ?: continue
                val map = found.getOrPut(pack.id) { HashMap() }
                val prev = map[n]
                if (prev == null || prev.length() < f.length()) map[n] = f
            }
        }
        return ALL.mapNotNull { p -> found[p.id]?.let { m -> FoundPack(p, m.mapValues { Uri.fromFile(it.value) }) } }
    }

    /** يصنّف قائمة ملفات (من المشاركة أو الاختيار المتعدد) إلى حزم */
    fun groupUris(uris: List<Pair<Uri, String>>): List<FoundPack> {
        val found = HashMap<String, HashMap<Int, Uri>>()
        for ((uri, name) in uris) {
            val (pack, n) = identify(name) ?: continue
            found.getOrPut(pack.id) { HashMap() }[n] = uri
        }
        return ALL.mapNotNull { p -> found[p.id]?.let { FoundPack(p, it) } }
    }
}

package org.murabbie.muhaddith.data

/** إعدادات التطبيق — تُحفظ في جدول settings داخل قاعدة المستخدم */
data class AppSettings(
    // المظهر
    val theme: String = "system",          // system | light | dark
    val fontScale: Float = 1.0f,           // ٠٫٨٥ … ١٫٥
    val serifFont: Boolean = true,         // خط مسنّن للمتون
    val showTashkeel: Boolean = true,      // إظهار التشكيل في المتون والأسانيد
    val showIsnadInResults: Boolean = false, // إظهار مطلع الإسناد في بطاقة النتيجة
    val highlight: Boolean = true,         // إبراز كلمات البحث
    // البحث
    val defaultLiteral: Boolean = true,
    val defaultMorph: Boolean = true,
    val defaultScope: String = "MATN",     // MATN | ISNAD | BOTH
    val resultLimit: Int = 200,            // ١٠٠ | ٢٠٠ | ٤٠٠
    val keepHistory: Boolean = true,
    val snippetLength: Int = 160,          // ١٢٠ | ١٦٠ | ٢٤٠
    // البيانات
    val dataFolderUri: String? = null,     // مجلد الأجزاء المختار (SAF)
    val autoScanOnOpen: Boolean = true,
    val keepScreenOnWhileImporting: Boolean = true,
    // التحميل
    val wifiOnly: Boolean = false,
    val notifyProgress: Boolean = true,
    val autoResume: Boolean = true,
    // نطاق الكتب (فارغ = الكل)
    val bookScope: Set<Long> = emptySet(),
    // البحث الدلالي
    val defaultSemantic: Boolean = true,
    val semanticWeight: Float = 0.9f,
    val releaseModelInBackground: Boolean = true,
    /** إظهار ملخّص أحكام العلماء (صحّحه/ضعّفه) في بطاقات نتائج البحث */
    val rulingsInResults: Boolean = true,
    /** دقة ربط الأحكام والمواضع بالحديث: ٠ دقيق جدًّا · ١ دقيق · ٢ واسع */
    val rulingPrecision: Int = 1,
    /** تنزيل حزمتي الأحكام والشروح تلقائيًّا وتحديثهما، وحزم البحث الدلالي الناقصة على الواي فاي */
    val autoDataUpdate: Boolean = true
) {
    val defaultEngines: Set<Engine>
        get() = buildSet {
            if (defaultLiteral) add(Engine.LITERAL)
            if (defaultMorph) add(Engine.MORPH)
            if (defaultSemantic) add(Engine.SEMANTIC)
            if (isEmpty()) add(Engine.LITERAL)
        }

    fun toMap(): Map<String, String> = mapOf(
        "theme" to theme, "fontScale" to fontScale.toString(), "serifFont" to serifFont.toString(),
        "showTashkeel" to showTashkeel.toString(), "showIsnadInResults" to showIsnadInResults.toString(),
        "highlight" to highlight.toString(), "defaultLiteral" to defaultLiteral.toString(),
        "defaultMorph" to defaultMorph.toString(), "defaultScope" to defaultScope,
        "resultLimit" to resultLimit.toString(), "keepHistory" to keepHistory.toString(),
        "snippetLength" to snippetLength.toString(), "dataFolderUri" to (dataFolderUri ?: ""),
        "autoScanOnOpen" to autoScanOnOpen.toString(),
        "keepScreenOnWhileImporting" to keepScreenOnWhileImporting.toString(),
        "wifiOnly" to wifiOnly.toString(), "notifyProgress" to notifyProgress.toString(),
        "autoResume" to autoResume.toString(), "bookScope" to bookScope.joinToString(","),
        "defaultSemantic" to defaultSemantic.toString(), "semanticWeight" to semanticWeight.toString(),
        "releaseModelInBackground" to releaseModelInBackground.toString(),
        "rulingsInResults" to rulingsInResults.toString(),
        "rulingPrecision" to rulingPrecision.toString(),
        "autoDataUpdate" to autoDataUpdate.toString()
    )

    companion object {
        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            return AppSettings(
                theme = m["theme"] ?: d.theme,
                fontScale = m["fontScale"]?.toFloatOrNull()?.coerceIn(0.85f, 1.5f) ?: d.fontScale,
                serifFont = b("serifFont", d.serifFont),
                showTashkeel = b("showTashkeel", d.showTashkeel),
                showIsnadInResults = b("showIsnadInResults", d.showIsnadInResults),
                highlight = b("highlight", d.highlight),
                defaultLiteral = b("defaultLiteral", d.defaultLiteral),
                defaultMorph = b("defaultMorph", d.defaultMorph),
                defaultScope = m["defaultScope"] ?: d.defaultScope,
                resultLimit = m["resultLimit"]?.toIntOrNull() ?: d.resultLimit,
                keepHistory = b("keepHistory", d.keepHistory),
                snippetLength = m["snippetLength"]?.toIntOrNull() ?: d.snippetLength,
                dataFolderUri = m["dataFolderUri"]?.takeIf { it.isNotBlank() },
                autoDataUpdate = b("autoDataUpdate", d.autoDataUpdate),
                autoScanOnOpen = b("autoScanOnOpen", d.autoScanOnOpen),
                keepScreenOnWhileImporting = b("keepScreenOnWhileImporting", d.keepScreenOnWhileImporting),
                wifiOnly = b("wifiOnly", d.wifiOnly), notifyProgress = b("notifyProgress", d.notifyProgress),
                autoResume = b("autoResume", d.autoResume),
                bookScope = (m["bookScope"] ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }.toSet(),
                defaultSemantic = b("defaultSemantic", d.defaultSemantic),
                semanticWeight = m["semanticWeight"]?.toFloatOrNull()?.coerceIn(0.2f, 2f) ?: d.semanticWeight,
                releaseModelInBackground = b("releaseModelInBackground", d.releaseModelInBackground),
                rulingsInResults = b("rulingsInResults", d.rulingsInResults),
                rulingPrecision = (m["rulingPrecision"]?.toIntOrNull() ?: d.rulingPrecision).coerceIn(0, 2)
            )
        }
    }
}

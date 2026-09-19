package org.murabbie.muhaddith.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.AppSettings
import org.murabbie.muhaddith.search.ArabicText

/** شاشة الإعدادات: البيانات، المظهر، البحث، النسخ الاحتياطي، عن التطبيق */
@Composable
fun SettingsScreen(vm: MuhaddithViewModel, state: UiState, onOpenData: () -> Unit) {
    val s = state.settings
    val ctx = LocalContext.current
    val d = state.dataset

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let {
            runCatching {
                ctx.contentResolver.openOutputStream(it)?.use { out -> out.write(vm.exportUserData().toByteArray()) }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val text = runCatching { ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } }.getOrNull()
            if (text != null) vm.importUserData(text)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ---------- البيانات ----------
        item {
            SettingsCard("البيانات والتخزين") {
                Text(
                    "المفعّل الآن: ${d?.name ?: "…"}" + if (d != null && !d.isEmpty) " · ${ArabicText.arabicDigits(d.hadiths)} رواية · ${ArabicText.arabicDigits(d.sizeBytes / 1_000_000)} م.ب" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenData, modifier = Modifier.fillMaxWidth()) { Text("إدارة حزم البيانات (تحميل · مجلدات · خادم)") }
                SwitchRow("فحص المجلدات تلقائيًّا عند فتح التطبيق", s.autoScanOnOpen) { vm.updateSettings { it.copy(autoScanOnOpen = !it.autoScanOnOpen) } }
            }
        }

        // ---------- التحميل ----------
        item {
            SettingsCard("التحميل") {
                SwitchRow("التنزيل عبر الواي فاي فقط", s.wifiOnly) { vm.updateSettings { it.copy(wifiOnly = !it.wifiOnly) } }
                SwitchRow("إشعار بتقدّم التحميل في الخلفية", s.notifyProgress) { vm.updateSettings { it.copy(notifyProgress = !it.notifyProgress) } }
                SwitchRow("استئناف تلقائي من حيث توقّف", s.autoResume) { vm.updateSettings { it.copy(autoResume = !it.autoResume) } }
                SwitchRow("إبقاء الشاشة مضاءة أثناء التحميل", s.keepScreenOnWhileImporting) { vm.updateSettings { it.copy(keepScreenOnWhileImporting = !it.keepScreenOnWhileImporting) } }
                Text("مكان التخزين (داخلي / بطاقة ذاكرة) ومصدر التحميل (هاتف / إنترنت / جهاز آخر) من «إدارة حزم البيانات».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- التحديثات: التطبيق والبيانات ----------
        item {
            SettingsCard("التحديثات") {
                Button(onClick = { vm.updateEverything() }, enabled = !state.updateChecking && !state.pack.busy, modifier = Modifier.fillMaxWidth()) { Text("تحقّق الآن وحدّث كل شيء") }
                Text("يُفحص الخادم عند كل تشغيل وكل عودة إلى التطبيق (مرة في الساعة على الأكثر)، فيُنزَّل تحديث التطبيق تلقائيًّا على الواي فاي ثم يبقى التثبيت بضغطة لأن النظام يطلبها، وتُنزَّل الحزم الأحدث تلقائيًّا (الكبيرة على الواي فاي). الزر أعلاه يطبّق الكل الآن.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("تحديث التطبيق", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text("الإصدار المثبَّت: ${ArabicText.arabicDigits(state.appVersionName)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                UpdateBlock(state, vm)
                Spacer(Modifier.height(12.dp))
                Text("تحديث البيانات", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                SwitchRow("تحديث التطبيق والحزم تلقائيًّا (الكبيرة على الواي فاي)", s.autoDataUpdate) { vm.updateSettings { it.copy(autoDataUpdate = !it.autoDataUpdate) } }
                Text("كل حزمة على الخادم أحدث من المثبَّتة (بتاريخ البناء أو البصمة) تُنزَّل وتُفعَّل في الخلفية بالترتيب: المتون ثم الأحكام ثم الشروح ثم النموذج والمتّجهات.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                val du = state.dataUpdates
                if (state.pack.remote.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.refreshManifest() }, enabled = !state.pack.loadingRemote) { Text("افحص الخادم") }
                        if (state.pack.loadingRemote) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        state.pack.remoteError?.let { Text("تعذّر ($it)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                } else if (du.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("كل البيانات المثبَّتة مطابقة لأحدث ما على الخادم ✓", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.refreshManifest() }) { Text("أعد الفحص") }
                    }
                } else {
                    du.forEach { u ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(u.pack.name, style = MaterialTheme.typography.titleSmall)
                                    Text(u.reason + " · ${ArabicText.arabicDigits(u.pack.sizeGz / 1_000_000)} م.ب", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = { vm.applyDataUpdates(listOf(u)) }, enabled = !state.pack.busy && state.pack.online) { Text(if (u.installed) "حدّث" else "نزّل") }
                            }
                        }
                    }
                    if (du.size > 1) TextButton(onClick = { vm.applyDataUpdates(du) }, enabled = !state.pack.busy && state.pack.online) { Text("نزّل الكل (${ArabicText.arabicDigits(du.sumOf { it.pack.sizeGz } / 1_000_000)} م.ب)") }
                }
                if (state.queued.isNotEmpty()) Text("في الانتظار: " + state.queued.joinToString("، "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenData) { Text("إدارة حزم البيانات…") }
            }
        }

        // ---------- نطاق الكتب ----------
        item {
            var showScope by remember { mutableStateOf(false) }
            SettingsCard("نطاق الكتب في البحث") {
                val n = s.bookScope.size
                Text(if (n == 0) "كل الكتب المثبَّتة (${ArabicText.arabicDigits(state.books.size)})" else "مقتصر على ${ArabicText.arabicDigits(n)} كتابًا",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                if (n in 1..6) Text(state.books.filter { it.id in s.bookScope }.joinToString("، ") { it.title }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showScope = true }) { Text("اختر الكتب…") }
                    if (n > 0) TextButton(onClick = { vm.setBookScope(emptySet()) }) { Text("الكل") }
                }
                // مجموعات جاهزة بضغطة
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, ids) in BookGroups.of(state.books)) {
                        if (ids.isEmpty() || ids.size == state.books.size) continue
                        FilterChip(selected = s.bookScope == ids, onClick = { vm.setBookScope(if (s.bookScope == ids) emptySet() else ids) }, label = { Text(label) })
                    }
                }
            }
            if (showScope) BookScopeDialog(state.books, s.bookScope, onSave = { vm.setBookScope(it); showScope = false }, onDismiss = { showScope = false })
        }

        // ---------- أحكام العلماء ومختلف الحديث ----------
        item {
            SettingsCard("أحكام العلماء ومختلف الحديث") {
                val a = state.ahkam
                Text(
                    if (a.installed) "مثبَّتة ✓ — ${ArabicText.arabicDigits(a.rulings)} حكمًا من ${ArabicText.arabicDigits(a.scholars)} عالمًا (منها ${ArabicText.arabicDigits(a.albani)} للألباني) على ${ArabicText.arabicDigits(a.clusters)} حديثًا مجمَّعًا، و${ArabicText.arabicDigits(a.books)} كتابًا في ${ArabicText.arabicDigits(a.sections)} مقطع (${ArabicText.arabicDigits(a.sizeBytes / 1_000_000)} م.ب)" + (a.built?.let { " · بُنيت $it" } ?: "")
                    else if (a.outdated) "الحزمة المثبَّتة من إصدار قديم بلا درجات ثقة الربط — نزّل حزمة الأحكام الجديدة وفعّلها."
                    else "غير مثبَّتة — حزمة muhaddith_ahkam: أحكام الأئمة (الترمذي، أبو حاتم، الدارقطني، الحاكم، ابن الجوزي، الذهبي، ابن حجر…) والمحققين (الألباني، شعيب الأرنؤوط، أحمد شاكر، زبير علي زئي…) على الأحاديث مع مصادرها، وكتب مختلف الحديث والناسخ والمنسوخ وأصول الجمع والتخريج والعلل كاملةً مربوطةً بالأحاديث.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenData) { Text(if (a.installed) "إدارة الحزم…" else "تثبيت الحزمة…") }
                    if (a.installed) TextButton(onClick = { vm.removeAhkam() }) { Text("حذف") }
                }
                SwitchRow("إظهار ملخّص الأحكام في نتائج البحث", s.rulingsInResults) { vm.updateSettings { it.copy(rulingsInResults = !it.rulingsInResults) } }
                Spacer(Modifier.height(6.dp))
                Text("دقة ربط الأحكام والمواضع بالحديث", style = MaterialTheme.typography.labelLarge)
                Text("ما نُصّ عليه على الرواية بعينها يُعرض دائمًا. أما ما رُبط بطرق الحديث الأخرى (كتب الألباني والتخريج وبلوغ المرام…) فيُربط بمطابقة نصية لها درجة ثقة؛ اختر الحدّ الذي تقبله كي لا يُنسب إلى الحديث ما قيل في حديث آخر مشابه.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    listOf(org.murabbie.muhaddith.data.RulingPrecision.STRICT, org.murabbie.muhaddith.data.RulingPrecision.PRECISE, org.murabbie.muhaddith.data.RulingPrecision.WIDE).forEach { p ->
                        Row(Modifier.fillMaxWidth().selectable(selected = s.rulingPrecision == p, onClick = { vm.updateSettings { it.copy(rulingPrecision = p) } }, role = Role.RadioButton).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.rulingPrecision == p, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(org.murabbie.muhaddith.data.RulingPrecision.label(p), style = MaterialTheme.typography.bodyMedium)
                                Text(org.murabbie.muhaddith.data.RulingPrecision.description(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("تظهر الأحكام في صفحة الحديث: ما قيل في الرواية نفسها، ثم ما قيل في طرق الحديث الأخرى. كل حكم معه مصدره ونصّه كما ورد، وزرّ لفتح موضعه في الكتاب. الأحكام المستخرجة من نصوص الكتب آليًّا قد تحتاج إلى تثبّت من الموضع.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- الشروح وأسباب الورود ----------
        item {
            SettingsCard("شروح الحديث وأسباب وروده وآثار الصحابة") {
                val h = state.shuruh
                Text(
                    if (h.installed) "مثبَّتة ✓ — ${ArabicText.arabicDigits(h.books)} كتابًا في ${ArabicText.arabicDigits(h.sections)} مقطع، ${ArabicText.arabicDigits(h.points)} استنباطًا لـ${ArabicText.arabicDigits(h.scholars)} عالمًا على ${ArabicText.arabicDigits(h.clusters)} حديثًا مجمَّعًا، و${ArabicText.arabicDigits(h.athar)} أثرًا عن الصحابة والتابعين، و${ArabicText.arabicDigits(h.stories)} رواية سياق (${ArabicText.arabicDigits(h.sizeBytes / 1_000_000)} م.ب)" + (h.built?.let { " · بُنيت ${ArabicText.arabicDigits(it)}" } ?: "")
                    else if (h.outdated) "الحزمة المثبَّتة من إصدار قديم (بلا آثار الصحابة ولا المصادر المرقَّمة) — تُحدَّث تلقائيًّا عند الاتصال، أو من بطاقة «التحديثات»."
                    else "غير مثبَّتة — حزمة muhaddith_shuruh: اختلاف الحديث للشافعي وتأويل مختلف الحديث لابن قتيبة وشرح معاني الآثار ومشكل الآثار للطحاوي ومشكل الحديث لابن فورك وكشف المشكل لابن الجوزي، وشروح الخطابي وابن بطال وابن عبد البر والبغوي والقاضي عياض والقرطبي والنووي وابن رجب وابن حجر والعيني والقسطلاني والقاري والمناوي والصنعاني والشوكاني والعظيم آبادي والمباركفوري، وابن عثيمين وابن باز والبسام، وكتابا أسباب الورود؛ مع استنباطات العلماء مصنَّفةً (عقدية/فقهية/اجتماعية…) ومرتَّبةً من الصحابة إلى المعاصرين، وسياق الحديث من الروايات الثابتة.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenData) { Text(if (h.installed) "إدارة الحزم…" else "تثبيت الحزمة…") }
                    if (h.installed) TextButton(onClick = { vm.removeShuruh() }) { Text("حذف") }
                }
                Text("تُربط مقاطع الشروح بالأحاديث بمطابقة نصية لها درجة ثقة تخضع لإعداد «دقة الربط» أعلاه، والاستنباطات مستخرجة آليًّا من نصوص الشروح (أقوال مسنَدة «قال فلان» وفوائد الشارح «وفي الحديث…») وتصنيفها تقريبي؛ فارجع إلى الموضع للتثبّت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- البحث الدلالي ----------
        item {
            SettingsCard("البحث الدلالي (بالمعنى)") {
                Text(
                    when {
                        state.semanticReady -> "جاهز ✓ — النموذج والمتجهات مثبَّتة للحزمة الحالية (${ArabicText.arabicDigits(state.semanticBytes / 1_000_000)} م.ب)"
                        state.semanticModel && !state.semanticVectors -> "النموذج مثبَّت، تنقص متجهات الحزمة الحالية (${d?.name ?: ""})"
                        !state.semanticModel && state.semanticVectors -> "المتجهات مثبَّتة، ينقص النموذج"
                        else -> "غير مثبَّت — يحتاج حزمتين: نموذج BGE-M3 (٨ أجزاء ≈ ٣٤٥ م.ب على الهاتف) ومتجهات الحزمة الحالية (التسعة: جزءان · الكاملة: ٩ أجزاء)"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenData) { Text("حزم الدلالي…") }
                    if (state.semanticModel || state.semanticVectors) TextButton(onClick = { vm.removeSemantic() }) { Text("حذف") }
                }
                SwitchRow("تفعيل الدلالي افتراضيًّا في البحث", s.defaultSemantic) { vm.updateSettings { it.copy(defaultSemantic = !it.defaultSemantic) } }
                Text("وزن الدلالي في الدمج: ${ArabicText.arabicDigits((s.semanticWeight * 100).toInt())}٪ (النصي ١٠٠٪، الصرفي ٧٢٪)", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.semanticWeight, onValueChange = { v -> vm.updateSettings { it.copy(semanticWeight = (Math.round(v * 10) / 10f).coerceIn(0.2f, 2f)) } }, valueRange = 0.2f..2f, steps = 17)
                SwitchRow("تحرير النموذج من الذاكرة عند مغادرة التطبيق", s.releaseModelInBackground) { vm.updateSettings { it.copy(releaseModelInBackground = !it.releaseModelInBackground) } }
                Text("النموذج BGE-M3 نفسه المستعمل في سطح المكتب (مكمَّمًا)، والمتجهات هي متجهات المحدِّث الأصلية؛ يعمل على الهاتف بلا إنترنت. أول بحث دلالي بعد فتح التطبيق يستغرق ثوانيَ لتحميل النموذج (≈ ٤٠٠ م.ب من الذاكرة).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- المظهر ----------
        item {
            SettingsCard("المظهر") {
                Text("السمة", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "حسب النظام", "light" to "فاتحة", "dark" to "داكنة").forEach { (k, l) ->
                        FilterChip(selected = s.theme == k, onClick = { vm.updateSettings { it.copy(theme = k) } }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("حجم الخط: ${ArabicText.arabicDigits((s.fontScale * 100).toInt())}٪", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = s.fontScale, onValueChange = { v -> vm.updateSettings { it.copy(fontScale = (Math.round(v * 20) / 20f).coerceIn(0.85f, 1.5f)) } },
                    valueRange = 0.85f..1.5f, steps = 12
                )
                Text("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ، وَإِنَّمَا لِكُلِّ امْرِئٍ مَا نَوَى", style = MatnStyle)
                SwitchRow("خط مسنَّن (نسخي) للمتون", s.serifFont) { vm.updateSettings { it.copy(serifFont = !it.serifFont) } }
                SwitchRow("إظهار التشكيل", s.showTashkeel) { vm.updateSettings { it.copy(showTashkeel = !it.showTashkeel) } }
                SwitchRow("إبراز كلمات البحث في النتائج", s.highlight) { vm.updateSettings { it.copy(highlight = !it.highlight) } }
                SwitchRow("إظهار مطلع الإسناد في بطاقة النتيجة", s.showIsnadInResults) { vm.updateSettings { it.copy(showIsnadInResults = !it.showIsnadInResults) } }
            }
        }

        // ---------- البحث ----------
        item {
            SettingsCard("البحث") {
                Text("المحركات الافتراضية", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.defaultLiteral, onClick = { vm.updateSettings { it.copy(defaultLiteral = !it.defaultLiteral || !it.defaultMorph) } }, label = { Text("نصي") })
                    FilterChip(selected = s.defaultMorph, onClick = { vm.updateSettings { it.copy(defaultMorph = !it.defaultMorph || !it.defaultLiteral) } }, label = { Text("صرفي") })
                }
                Spacer(Modifier.height(10.dp))
                Text("النطاق الافتراضي", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    org.murabbie.muhaddith.data.SearchScope.entries.forEach { sc ->
                        FilterChip(selected = s.defaultScope == sc.name, onClick = { vm.updateSettings { it.copy(defaultScope = sc.name) } }, label = { Text(sc.label) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("أقصى عدد للنتائج", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 400).forEach { n ->
                        FilterChip(selected = s.resultLimit == n, onClick = { vm.updateSettings { it.copy(resultLimit = n) } }, label = { Text(ArabicText.arabicDigits(n)) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("طول المقتطف في النتيجة", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(120 to "قصير", 160 to "متوسط", 240 to "طويل").forEach { (n, l) ->
                        FilterChip(selected = s.snippetLength == n, onClick = { vm.updateSettings { it.copy(snippetLength = n) } }, label = { Text(l) })
                    }
                }
                SwitchRow("حفظ سجل عمليات البحث", s.keepHistory) { vm.updateSettings { it.copy(keepHistory = !it.keepHistory) } }
                TextButton(onClick = { vm.clearHistory() }) { Text("مسح السجل الآن") }
                Text("صيغة الاستعلام: الكلمات بينها «و» ضمنية · \"بين اقتباس\" عبارة بترتيبها · -كلمة تستبعدها",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- النسخ الاحتياطي ----------
        item {
            SettingsCard("النسخ الاحتياطي") {
                Text("المجموعات وخطط البحث تُحفظ على الهاتف فقط. صدّرها إلى ملف لتنقلها إلى هاتف آخر أو تحتفظ بها.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("muhaddith-backup.json") }) { Text("تصدير") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("استيراد") }
                }
                Spacer(Modifier.height(8.dp))
                var confirmReset by remember { mutableStateOf(false) }
                TextButton(onClick = { confirmReset = true }) { Text("استعادة الإعدادات الافتراضية") }
                if (confirmReset) AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("استعادة الإعدادات الافتراضية؟") },
                    text = { Text("تعود كل الخيارات (المظهر، والبحث، ونطاق الكتب، والتحميل) إلى قيمها الأصلية. لا تُمسّ الحزم المثبَّتة ولا مجموعاتك ولا سجلّ البحث.") },
                    confirmButton = { TextButton(onClick = { vm.resetSettings(); confirmReset = false }) { Text("استعادة") } },
                    dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("إلغاء") } }
                )
            }
        }

        // ---------- عن التطبيق ----------
        item {
            SettingsCard("عن التطبيق") {
                val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                Text("المحدِّث — الإصدار ${ArabicText.arabicDigits(ver)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("محرّك بحث في الحديث النبوي يعمل على جهازك بلا إنترنت. البيانات من قاعدة «المحدِّث» (muhaddith.murabbie.org) كما هي في نسخة سطح المكتب: ١٤٠٠ كتاب، ٤٦٣٬٧٣٣ رواية، ٤٩٬٨٤٥ راويًا. البحث الصرفي بمعجم المحدِّث (٥٨٧ ألف صورة صرفية). البحث الدلالي (BGE-M3) يعمل على الهاتف بعد تثبيت حزمتيه. الحزم كلها تُنزَّل من داخل التطبيق.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("تنبيه علمي: التطبيق أداة بحث وترشيح واستكشاف، وليس مصدرًا مستقلًّا للحكم على الحديث.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** فحص التحديث وتنزيله وتثبيته من داخل التطبيق */
@Composable
fun UpdateBlock(state: UiState, vm: MuhaddithViewModel) {
    val ctx = LocalContext.current
    val u = state.updateAvailable
    if (u != null) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("تحديث متاح: الإصدار ${ArabicText.arabicDigits(u.versionName)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.updateFile != null) Button(onClick = { vm.installUpdate(ctx) }) { Text("ثبّت الآن") }
                    else Button(onClick = { vm.downloadUpdate() }, enabled = !state.pack.busy && state.pack.online) { Text("نزّل التحديث") }
                }
                Text("بعد التنزيل يفتح مثبّت النظام؛ وقد يطلب أول مرة السماح لالمحدِّث بتثبيت التطبيقات. بياناتك وحزمك تبقى كما هي.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    } else if (!org.murabbie.muhaddith.BuildConfig.SELF_UPDATE) {
        Text("تحديثات التطبيق تصل من متجر Google Play.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.checkForUpdate() }, enabled = !state.updateChecking) { Text("التحقق من وجود تحديث") }
            if (state.updateChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
    state.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** مجموعات الكتب الجاهزة (بالأولوية أو بالعنوان) */
object BookGroups {
    fun of(books: List<org.murabbie.muhaddith.data.Book>): List<Pair<String, Set<Long>>> {
        fun t(pred: (org.murabbie.muhaddith.data.Book) -> Boolean) = books.filter(pred).map { it.id }.toSet()
        return listOf(
            "الصحيحان" to t { it.priority <= 1 },
            "الكتب التسعة" to t { it.priority <= 8 },
            "الصحاح والسنن" to t { it.priority <= 12 || it.title.startsWith("صحيح") || it.title.startsWith("سنن") || it.title.startsWith("الصحيح") || it.title.startsWith("السنن") },
            "المسانيد" to t { it.title.startsWith("مسند") || it.title.startsWith("المسند") },
            "المعاجم" to t { it.title.contains("معجم") },
            "المصنفات" to t { it.title.startsWith("مصنف") || it.title.startsWith("المصنف") },
        )
    }
}

/** اختيار عدة كتب لنطاق البحث */
@Composable
fun BookScopeDialog(books: List<org.murabbie.muhaddith.data.Book>, selected: Set<Long>, onSave: (Set<Long>) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Long>().apply { addAll(selected) } }
    val filtered = remember(q, books) {
        val n = ArabicText.normalize(q)
        (if (n.isEmpty()) books else books.filter { ArabicText.normalize(it.title).contains(n) || ArabicText.normalize(it.author ?: "").contains(n) }).take(400)
    }
    val groups = remember(books) { BookGroups.of(books) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("نطاق الكتب (${ArabicText.arabicDigits(chosen.size)} من ${ArabicText.arabicDigits(books.size)})") },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("اسم الكتاب أو مؤلفه") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val allShown = filtered.isNotEmpty() && filtered.all { it.id in chosen }
                    TextButton(onClick = { if (allShown) filtered.forEach { chosen.remove(it.id) } else filtered.forEach { if (it.id !in chosen) chosen.add(it.id) } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (allShown) (if (q.isBlank()) "إلغاء تحديد الكل" else "إلغاء المعروض") else (if (q.isBlank()) "تحديد الكل" else "تحديد المعروض (${ArabicText.arabicDigits(filtered.size)})"))
                    }
                    TextButton(onClick = { val cur = chosen.toSet(); chosen.clear(); chosen.addAll(books.map { it.id }.filter { it !in cur }) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("عكس") }
                    for ((label, ids) in groups) if (ids.isNotEmpty()) {
                        FilterChip(selected = ids.all { it in chosen }, onClick = { if (ids.all { it in chosen }) chosen.removeAll(ids) else ids.forEach { if (it !in chosen) chosen.add(it) } }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Text("بلا اختيار = البحث في كل الكتب.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered.size, key = { filtered[it].id }) { i ->
                        val b = filtered[i]
                        Row(Modifier.fillMaxWidth().clickable { if (b.id in chosen) chosen.remove(b.id) else chosen.add(b.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = b.id in chosen, onCheckedChange = { if (b.id in chosen) chosen.remove(b.id) else chosen.add(b.id) })
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium)
                                Text("${b.author ?: ""} · ${ArabicText.arabicDigits(b.hadithCount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(if (chosen.size == books.size) emptySet() else chosen.toSet()) }) { Text("حفظ") } },
        dismissButton = { Row { TextButton(onClick = { chosen.clear() }) { Text("مسح") }; TextButton(onClick = onDismiss) { Text("إلغاء") } } }
    )
}

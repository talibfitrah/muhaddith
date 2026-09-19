package org.murabbie.muhaddith.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.PackService
import org.murabbie.muhaddith.search.ArabicText

private enum class Source(val label: String) { PHONE("من الهاتف"), INTERNET("من الإنترنت"), DEVICE("من جهاز آخر") }

/** إدارة حزم البيانات: مصدر التحميل (هاتف / إنترنت / جهاز آخر)، التحكم بالتحميل، المشاركة، مكان التخزين */
@Composable
fun DataScreen(vm: MuhaddithViewModel, state: UiState) {
    val d = state.dataset
    val p = state.pack
    val s = state.settings
    val ctx = LocalContext.current
    var source by remember { mutableStateOf(if (p.found.isNotEmpty() || !p.online) Source.PHONE else Source.INTERNET) }
    val work by vm.workState().collectAsState()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotif() {
        if (s.notifyProgress && android.os.Build.VERSION.SDK_INT >= 33 && !PackService.hasNotifPermission(ctx))
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) { ensureNotif(); vm.importPack(uris) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        vm.refreshAccess(); if (granted) vm.scanPublic()
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { val had = p.allFilesAccess; vm.refreshAccess(); if (!had) vm.autoScan() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        tree?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.scanFolder(it, it.lastPathSegment?.substringAfterLast(':')?.ifBlank { null })
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ---------- الحالة: ما المثبَّت وما يعمل ----------
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    SectionLabel("حالة البيانات على هذا الجهاز")
                    val onC = MaterialTheme.colorScheme.onPrimaryContainer
                    @Composable fun StatusRow(title: String, ok: Boolean?, detail: String, action: (@Composable () -> Unit)? = null) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (ok) { true -> "✓"; false -> "✗"; null -> "!" }, style = MaterialTheme.typography.titleMedium,
                                color = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.tertiary }, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleSmall, color = onC)
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = onC)
                            }
                            action?.invoke()
                        }
                    }
                    val corpusOk = d != null && !d.isEmpty
                    StatusRow("المتون" + (if (corpusOk) ": ${d!!.name}" else ""), corpusOk,
                        if (corpusOk) "${ArabicText.arabicDigits(d!!.hadiths)} رواية · ${ArabicText.arabicDigits(d.books)} كتاب · ${ArabicText.arabicDigits(d.sizeBytes / 1_000_000)} م.ب · ${d.location}" else "غير مثبَّتة — نزّل الكتب التسعة أو الحزمة الكاملة أدناه")
                    val a = state.ahkam
                    StatusRow("أحكام العلماء ومختلف الحديث", if (a.installed) true else if (a.outdated) null else false,
                        if (a.installed) "${ArabicText.arabicDigits(a.rulings)} حكمًا من ${ArabicText.arabicDigits(a.scholars)} عالمًا · ${ArabicText.arabicDigits(a.books)} كتابًا · ${ArabicText.arabicDigits(a.sizeBytes / 1_000_000)} م.ب" + (a.built?.let { " · بُنيت ${ArabicText.arabicDigits(it)}" } ?: "")
                        else if (a.outdated) "مثبَّتة لكنها من إصدار قديم — تُحدَّث تلقائيًّا، أو من قائمة التحديثات" else "غير مثبَّتة — تُنزَّل تلقائيًّا بعد المتون (أو من القائمة أدناه)",
                        if (a.installed) ({ TextButton(onClick = { vm.removeAhkam() }) { Text("حذف") } }) else null)
                    val sh = state.shuruh
                    StatusRow("شروح الحديث وأسباب وروده وآثار الصحابة", if (sh.installed) true else if (sh.outdated) null else false,
                        if (sh.installed) "${ArabicText.arabicDigits(sh.books)} كتابًا · ${ArabicText.arabicDigits(sh.points)} استنباطًا · ${ArabicText.arabicDigits(sh.athar)} أثرًا · ${ArabicText.arabicDigits(sh.sizeBytes / 1_000_000)} م.ب" + (sh.built?.let { " · بُنيت ${ArabicText.arabicDigits(it)}" } ?: "")
                        else if (sh.outdated) "مثبَّتة لكنها من إصدار قديم (بلا آثار) — تُحدَّث تلقائيًّا، أو من قائمة التحديثات" else "غير مثبَّتة — تُنزَّل تلقائيًّا بعد المتون (أو من القائمة أدناه)",
                        if (sh.installed) ({ TextButton(onClick = { vm.removeShuruh() }) { Text("حذف") } }) else null)
                    val semProblem = vm.semanticProblem()
                    val semNeed = vm.semanticPacks()
                    val semQueued = semNeed.any { n -> state.queued.any { it.contains(n.name) } }
                    StatusRow("البحث الدلالي (بالمعنى)", semProblem == null,
                        if (semProblem == null) "يعمل ✓ — النموذج والمتّجهات مثبَّتان (${ArabicText.arabicDigits(state.semanticBytes / 1_000_000)} م.ب)"
                        else "لا يعمل: $semProblem" + (if (state.semanticModel || state.semanticVectors) " · النموذج ${if (state.semanticModel) "✓" else "✗"} · المتّجهات ${if (state.semanticVectors) "✓" else "✗"}" else "") +
                            (if (semNeed.isNotEmpty()) " — الناقص (${ArabicText.arabicDigits(semNeed.sumOf { it.sizeGz } / 1_000_000)} م.ب) يُنزَّل تلقائيًّا على الواي فاي" + (if (semQueued) " (في الانتظار)" else "") else ""),
                        when {
                            semNeed.isNotEmpty() && p.online && (!s.wifiOnly || p.onWifi) -> ({ TextButton(onClick = { ensureNotif(); vm.downloadPacks(semNeed) }, enabled = !p.busy && !semQueued) { Text("نزّل الآن") } })
                            state.semanticModel || state.semanticVectors -> ({ TextButton(onClick = { vm.removeSemantic() }) { Text("حذف") } })
                            else -> null
                        })
                    if (state.updateAvailable != null || state.dataUpdates.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text((if (state.updateAvailable != null) "تحديث للتطبيق متاح · " else "") + (if (state.dataUpdates.isNotEmpty()) "تحديثات بيانات: ${state.dataUpdates.joinToString("، ") { it.pack.name }}" else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (state.queued.isNotEmpty()) Text("في الانتظار: " + state.queued.joinToString("، "), style = MaterialTheme.typography.labelSmall, color = onC)
                }
            }
        }

        // ---------- التحميل الجاري ----------
        if (p.busy) {
            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        val phaseLabel = when (p.phase) { 0 -> "تنزيل"; 1 -> "تحقّق من سلامة الأجزاء"; 2 -> "فكّ الضغط والتثبيت"; else -> "نقل" }
                        Text("${p.message ?: ""}", style = MaterialTheme.typography.titleMedium)
                        Text(phaseLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        val pct = if (p.total > 0) " (${ArabicText.arabicDigits((p.done * 100 / p.total).toInt())}٪)" else ""
                        Text("${ArabicText.arabicDigits(p.done / 1_000_000)} م.ب" + (if (p.total > 0) " من ${ArabicText.arabicDigits(p.total / 1_000_000)}" else "") + pct +
                                (if (work.speedBps > 0 && p.phase == 0) " · ${ArabicText.arabicDigits(work.speedBps / 1000)} ك.ب/ث" else "") +
                                (if (work.etaSec >= 0 && p.phase == 0) " · يتبقى ${ArabicText.arabicDigits(work.etaSec / 60)} د ${ArabicText.arabicDigits(work.etaSec % 60)} ث" else ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.cancelWork() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("إيقاف") }
                        }
                        Text("يستمر التحميل في الخلفية مع إشعار، ويُستأنف من حيث توقّف إن انقطع.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@LazyColumn
        }

        p.error?.let { item { NoticeBanner("تعذّر: $it") } }
        p.message?.let { item { NoticeBanner(it) } }

        // ---------- اختيار المصدر ----------
        item {
            Text("مصدر التحميل", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Source.entries.forEachIndexed { i, src ->
                    SegmentedButton(selected = source == src, onClick = { source = src; if (src == Source.INTERNET && p.remote.isEmpty() && !p.loadingRemote) vm.refreshManifest() },
                        shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size)) { Text(src.label) }
                }
            }
        }

        when (source) {
            Source.PHONE -> item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("نزّل أجزاء الحزمة إلى الهاتف (الكتب التسعة: ٣ أجزاء · الكاملة: ٢٠ جزءًا). وللبحث الدلالي: نموذج BGE-M3 (٨ أجزاء) + متجهات الحزمة نفسها (التسعة: جزءان · الكاملة: ٩ أجزاء). التطبيق يتعرّف عليها ويجمعها ويتحقّق منها بنفسه.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        if (org.murabbie.muhaddith.BuildConfig.ALL_FILES_ACCESS) Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("تمكين الوصول إلى مجلدات الهاتف", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text(if (p.allFilesAccess) "مفعّل — يفحص التنزيلات والمستندات وتيليغرام وواتساب" else "غير مفعّل — يلزم اختيار المجلد يدويًّا",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                    Switch(checked = p.allFilesAccess, onCheckedChange = {
                                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                                            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                                            runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                                        } else storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                    })
                                }
                                if (p.allFilesAccess) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { vm.scanPublic() }, modifier = Modifier.fillMaxWidth()) { Text("افحص مجلدات الهاتف الآن") }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (p.folderName == null || p.allFilesAccess) "اختر مجلدًا بعينه…" else "أعد فحص المجلد (${p.folderName})")
                        }
                        if (p.scanning) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                        if (p.folderName != null && !p.scanning && p.found.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("لم أجد أجزاء حزمة. تأكد أن أسماء الملفات تبدأ بـ muhaddith_corpus_ وأنها ليست في مجلد فرعي.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        p.found.forEach { fp ->
                            Spacer(Modifier.height(10.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(fp.pack.name + when (fp.pack.kind) { "model" -> " (دلالي)"; "vectors" -> " (دلالي)"; "ahkam" -> " (أحكام)"; "shuruh" -> " (شروح)"; else -> "" }, style = MaterialTheme.typography.titleMedium)
                                    Text("وُجد ${ArabicText.arabicDigits(fp.parts.size)} من ${ArabicText.arabicDigits(fp.pack.partCount)} أجزاء" +
                                            if (fp.complete) " — مكتملة ✓" else " — ناقص: " + fp.missing.joinToString("، ") { ArabicText.arabicDigits("%03d".format(it)) },
                                        style = MaterialTheme.typography.bodySmall, color = if (fp.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                    if (fp.complete) { Spacer(Modifier.height(8.dp)); Button(onClick = { ensureNotif(); vm.importFound(fp) }) { Text("فعّل «${fp.pack.name}»") } }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("أو اختر الملفات يدويًّا…") }
                        Text("أو حدّد الأجزاء في مدير الملفات ثم «مشاركة ← المحدِّث».", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Source.INTERNET -> item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (!p.online) "لا اتصال بالإنترنت" else if (p.onWifi) "متصل بالواي فاي" else "متصل ببيانات الهاتف",
                                style = MaterialTheme.typography.labelLarge, color = if (p.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.refreshManifest() }) { Text("تحديث") }
                        }
                        SwitchRow("التنزيل عبر الواي فاي فقط", s.wifiOnly) { vm.updateSettings { it.copy(wifiOnly = !it.wifiOnly) } }
                        if (p.loadingRemote) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        p.remoteError?.let { Text("لا حزم على الخادم بعد ($it).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (p.remote.any { it.id == "corpus_tisa" || it.id == "corpus_full" }) {
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("بضغطة واحدة: المتون + أحكام العلماء + الشروح", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("تُنزَّل الحزم متتابعةً وتُفعَّل تلقائيًّا، ويُتخطّى ما هو مثبَّت. البحث الدلالي (النموذج والمتّجهات) يمكن إضافته بعدُ من القائمة أدناه.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.height(8.dp))
                                    val canDl = p.online && (!s.wifiOnly || p.onWifi) && !p.busy
                                    for (full in listOf(false, true)) {
                                        val set = vm.recommendedPacks(full)
                                        val label = (if (full) "الحزمة الكاملة (١٤٠٠ كتاب)" else "الكتب التسعة") + " — " +
                                            if (set.isEmpty()) "مثبَّتة كلها ✓" else "تنزيل ${ArabicText.arabicDigits(set.sumOf { it.sizeGz } / 1_000_000)} م.ب"
                                        Button(onClick = { ensureNotif(); vm.downloadPacks(set) }, enabled = canDl && set.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(label) }
                                        if (!full) Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                        // البحث الدلالي: زر واحد ينزّل النموذج ومتّجهات الحزمة المثبَّتة معًا
                        val sem = vm.semanticPacks()
                        if (p.remote.any { it.id == "model_bge" } && d != null && !d.isEmpty) {
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("البحث الدلالي (بالمعنى)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text(vm.semanticProblem()?.let { "الحالة: $it" } ?: "يعمل ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("يلزمه ملفان: النموذج (مرة واحدة) ومتّجهات الحزمة المثبَّتة (${if (d.isFull) "الكاملة" else "التسعة"}). الزر ينزّل الناقص منهما فقط.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Spacer(Modifier.height(6.dp))
                                    Button(onClick = { ensureNotif(); vm.downloadPacks(sem) }, enabled = sem.isNotEmpty() && p.online && (!s.wifiOnly || p.onWifi)) {
                                        Text(if (sem.isEmpty()) "مثبَّت كاملًا ✓" else "نزّل الناقص (${ArabicText.arabicDigits(sem.sumOf { it.sizeGz } / 1_000_000)} م.ب): " + sem.joinToString(" + ") { it.name })
                                    }
                                }
                            }
                        }
                        val corpusDs = d?.let { if (it.isFull) "full" else "tisa" }
                        p.remote.filter { rp -> !(rp.id.startsWith("vectors_") && corpusDs != null && rp.id != "vectors_$corpusDs") }.forEach { rp ->
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(rp.name, style = MaterialTheme.typography.titleMedium)
                                    if (rp.description.isNotBlank()) Text(rp.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("تنزيل ${ArabicText.arabicDigits(rp.sizeGz / 1_000_000)} م.ب · يشغل ${ArabicText.arabicDigits(rp.sizeDb / 1_000_000)} م.ب", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    val installedHere = when (rp.id) {
                                        "corpus_tisa" -> d?.let { !it.isEmpty && !it.isFull } ?: false
                                        "corpus_full" -> d?.let { !it.isEmpty && it.isFull } ?: false
                                        "ahkam" -> state.ahkam.installed
                                        "shuruh" -> state.shuruh.installed
                                        "model_bge" -> state.semanticModel
                                        else -> rp.id.startsWith("vectors_") && state.semanticVectors
                                    }
                                    val upd = state.dataUpdates.firstOrNull { it.pack.id == rp.id }
                                    if (installedHere) Text(if (upd != null) "مثبَّتة — ${upd.reason}" else "مثبَّتة ✓", style = MaterialTheme.typography.labelSmall, color = if (upd != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                    Button(onClick = { ensureNotif(); vm.downloadPack(rp) }, enabled = p.online && (!s.wifiOnly || p.onWifi)) {
                                        Text(if (p.partialBytes > 0) "استأنف التنزيل" else if (installedHere && upd != null) "حدّث" else if (installedHere) "أعد التنزيل" else "نزّل وفعّل")
                                    }
                                }
                            }
                        }
                        if (p.partialBytes > 0) TextButton(onClick = { vm.deletePartial() }) { Text("احذف التنزيل غير المكتمل (${ArabicText.arabicDigits(p.partialBytes / 1_000_000)} م.ب)") }
                        var editUrl by remember { mutableStateOf(false) }
                        var url by remember(state.packsBaseUrl) { mutableStateOf(state.packsBaseUrl) }
                        val isNas = org.murabbie.muhaddith.data.PackSource.parseSyno(state.packsBaseUrl) != null
                        if (!editUrl) TextButton(onClick = { editUrl = true }) { Text(if (state.packsBaseUrl == org.murabbie.muhaddith.data.DataPackManager.NAS_BASE_URL) "المصدر: مجلد التطبيق على NAS المؤلّف" else "عنوان الخادم: ${state.packsBaseUrl}") }
                        else Column {
                            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("مجلد الحزم (فيه manifest.json) أو رابط مشاركة NAS") }, modifier = Modifier.fillMaxWidth())
                            Text("يقبل مجلد ويب عاديًّا، أو رابط مشاركة مجلد سينولوجي مثل http://nas…:5000/sharing/XXXX/muhaddith", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton(onClick = { vm.setPacksBaseUrl(url); editUrl = false }) { Text("حفظ") }
                                TextButton(onClick = { editUrl = false }) { Text("إلغاء") }
                                if (!isNas || state.packsBaseUrl != org.murabbie.muhaddith.data.DataPackManager.NAS_BASE_URL) TextButton(onClick = { vm.setPacksBaseUrl(org.murabbie.muhaddith.data.DataPackManager.NAS_BASE_URL); editUrl = false }) { Text("مجلد NAS") }
                            }
                        }
                    }
                }
            }

            Source.DEVICE -> item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("انقل الحزمة من هاتف أو تابلت آخر مثبَّتة عليه — على شبكة الواي فاي نفسها، بلا إنترنت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("على الجهاز الذي فيه الحزمة", style = MaterialTheme.typography.labelLarge)
                        if (!p.sharing) {
                            Button(onClick = { vm.startSharing() }, enabled = d != null && !d.isEmpty, modifier = Modifier.fillMaxWidth()) { Text("شارك حزمتي مع جهاز آخر") }
                            if (d == null || d.isEmpty) Text("لا حزمة مثبَّتة هنا لمشاركتها.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("المشاركة تعمل — أدخل هذا العنوان في الجهاز الآخر:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(p.shareUrl ?: "لا عنوان — تأكد من الاتصال بالواي فاي", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text((if (p.shareHashing) "يحسب بصمة الملف… (يمكن البدء الآن)" else "جاهز") + (if (p.servedBytes > 0) " · أُرسل ${ArabicText.arabicDigits(p.servedBytes / 1_000_000)} م.ب" else ""),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(onClick = { vm.stopSharing() }) { Text("أوقف المشاركة") }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("على الجهاز الذي يستقبل", style = MaterialTheme.typography.labelLarge)
                        var addr by remember { mutableStateOf("") }
                        OutlinedTextField(value = addr, onValueChange = { addr = it }, singleLine = true, label = { Text("عنوان الجهاز الآخر (مثل 192.168.1.5:8765)") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { vm.downloadFromDevice(addr); source = Source.INTERNET }, enabled = addr.isNotBlank()) { Text("اتصل واعرض الحزمة") }
                        Text("بعد الاتصال تظهر الحزمة في تبويب «من الإنترنت» (المصدر نفسه، لكن عبر الشبكة المحلية) — اضغط «نزّل وفعّل».", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---------- سجلّ العمليات ----------
        item {
            var showLog by remember { mutableStateOf(false) }
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("سجلّ التنزيل والتثبيت", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showLog = !showLog; if (showLog) vm.packLogRefresh() }) { Text(if (showLog) "إخفاء" else "اعرض") }
                    }
                    Text("إن تعثّر تنزيل أو تثبيت فانسخ هذا السجلّ وأرسله ليُعرف السبب.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showLog) {
                        Spacer(Modifier.height(6.dp))
                        if (state.packLog.isEmpty()) Text("لا شيء بعد.", style = MaterialTheme.typography.bodySmall)
                        state.packLog.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("muhaddith-log", state.packLog.joinToString("\n")))
                            }) { Text("نسخ") }
                            TextButton(onClick = { vm.clearPackLog() }) { Text("مسح") }
                        }
                    }
                }
            }
        }

        // ---------- مكان التخزين ----------
        item {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("مكان تخزين الحزمة", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val choice = org.murabbie.muhaddith.data.MuhaddithDatabase.storageChoice(ctx)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = choice == "internal", onClick = { if (choice != "internal") vm.moveStorage("internal") }, label = { Text("الذاكرة الداخلية") })
                        FilterChip(selected = choice == "sd", enabled = p.sdAvailable, onClick = { if (choice != "sd") vm.moveStorage("sd") }, label = { Text(if (p.sdAvailable) "بطاقة الذاكرة" else "لا بطاقة ذاكرة") })
                    }
                    Text("المساحة المتاحة: ${ArabicText.arabicDigits(org.murabbie.muhaddith.data.MuhaddithDatabase.storageDir(ctx).usableSpace / 1_000_000)} م.ب" +
                            " — الكتب التسعة تحتاج ≈ ٢٠٠ م.ب أثناء التثبيت، والكاملة ≈ ١٫٦ ج.ب أثناء التثبيت ثم ٩٢٠ م.ب",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (d?.isImported == true) {
            item { OutlinedButton(onClick = { vm.removeInstalledPack() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("احذف الحزمة من الجهاز") } }
        }
    }
}

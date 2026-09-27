package org.murabbie.muhaddith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.*
import org.murabbie.muhaddith.search.ArabicText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ====================== ١) سبب الحديث وسياقه ====================== */

@Composable
fun StorySection(stories: List<Story>?, asbab: List<PassageRef>?, installed: Boolean, onOpenHadith: (Long) -> Unit, onOpenSection: (Long) -> Unit) {
    if (!installed) return
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            var open by rememberSaveable { mutableStateOf(false) }
            FoldHeader("سبب الحديث وسياقه", open, { open = !open }, count = if (stories != null && asbab != null) stories.size + asbab.size else null)
            if (!open) return@Column
            when {
                stories == null || asbab == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                stories.isEmpty() && asbab.isEmpty() -> Text("لم يُعثر على سبب ورود مدوَّن ولا رواية ثابتة تحمل قصة الحديث في الحزمة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    if (asbab.isNotEmpty()) {
                        Text("في كتب أسباب الورود (${ArabicText.arabicDigits(asbab.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        asbab.forEach { p -> PassageRow(p) { onOpenSection(p.sectionId) } }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (stories.isNotEmpty()) {
                        Text("روايات ثابتة تحمل سياق القصة عن الصحابي (${ArabicText.arabicDigits(stories.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        Text("اختيرت آليًّا من روايات الحديث المجمَّع في الصحيحين أو بحكم صحيح/حسن، لاحتوائها سياقًا (سؤال، واقعة، سبب نزول…).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stories.forEach { st ->
                            Surface(onClick = { onOpenHadith(st.hadith.id) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(st.hadith.bookTitle + (st.hadith.hadithNum?.let { " (${ArabicText.arabicDigits(it)})" } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                        GradePill(st.hadith.hokmLabel)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(display(st.hadith.matn), style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ====================== ٢) الشروح والاستنباطات ====================== */

private fun ar(n: Int) = ArabicText.arabicDigits(n)

/** بطاقة نقطة واحدة (قول أو أثر) مرقَّمة داخل عالمها */
@Composable
fun PointCard(p: SharhPoint, num: Int, onOpenSection: (Long) -> Unit) {
    var expanded by remember(p.id) { mutableStateOf(false) }
    Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text("${ar(num)}. ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                if (p.isLemma && p.text.startsWith("قوله «") && p.text.contains("»:")) {
                    val lemma = p.text.substringAfter("قوله «").substringBefore("»:"); val rest = p.text.substringAfter("»:").trim()
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)) { append("قوله «" + display(lemma) + "»: ") }
                        append(display(rest))
                    }, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 6, modifier = Modifier.weight(1f))
                } else Text(display(p.text), style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 6, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            // المصادر: الأثر الواحد قد يكون في كتب عدة فتُذكر كلها تحته مرة واحدة
            val srcs = if (p.sources.isNotEmpty()) p.sources else listOf(PointSource(p.bookId, p.bookTitle, p.author, p.vol, p.page, p.sectionId))
            Text((if (p.isAthar) "رواه/ذكره: " else "المصدر: ") + srcs.joinToString("؛ ") { it.bookTitle + " — " + it.author + (it.ref.takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" } ?: "") },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Text((if (p.isAthar) "أثر" else if (p.isLemma) "شرح لفظ من المتن" else if (p.isAuthor) "من كلام الشارح" else "نقله ${p.author}") + " · ${if (p.isLemma) "شرح ألفاظ المتن" else p.category} · ثقة الربط ${ar((p.conf * 100).toInt())}٪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { srcs.forEachIndexed { i, src -> TextButton(onClick = { onOpenSection(src.sectionId) }) { Text(if (srcs.size == 1) "افتح الموضع" else "الموضع ${ar(i + 1)}") } } }
            }
        }
    }
}

/** رأس عالم واحد: «الإمام فلان (ت … هـ)» ثم نقاطه المرقَّمة */
@Composable
fun ScholarBlock(scholar: String, death: Int?, isAthar: Boolean, layer: Int, points: List<SharhPoint>, onOpenSection: (Long) -> Unit) {
    val d = death?.let { " (ت ${ar(it)} هـ)" } ?: ""
    val imam = !isAthar && layer >= 2 && !scholar.startsWith("ابن") && !scholar.startsWith("أبو")
    val title = if (isAthar) "عن " + scholar + (if (layer == 0) " رضي الله عنه" else "") + d else (if (imam) "الإمام " else "") + scholar + d
    Spacer(Modifier.height(6.dp))
    Text(title + (if (isAthar) " (${ar(points.size)})" else ": استنباطاته (${ar(points.size)})"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    val ordered = points.sortedWith(compareBy({ if (it.isLemma) 0 else if (it.isAthar) 1 else if (it.isAuthor) 2 else 3 }, { it.id }))
    ordered.forEachIndexed { i, p -> PointCard(p, i + 1, onOpenSection) }
}

/** نوع واحد (آثار / صنف من الأقوال): مطويّ؛ داخله العلماء بترتيب الوفاة، وتحت كل عالم نقاطه ١ ٢ ٣ */
@Composable
fun PointGroup(title: String, list: List<SharhPoint>, onOpenSection: (Long) -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(10.dp), color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title (${ar(list.size)})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) {
        // العلماء بترتيب الوفاة (المجهول الوفاة آخرًا)، ثم داخل كل عالم النقاط بترتيب ورودها
        val byScholar = list.groupBy { it.scholar }.entries.sortedWith(compareBy({ it.value.first().death ?: 99999 }, { it.key }))
        var shown by rememberSaveable(title) { mutableIntStateOf(8) }
        Column(Modifier.padding(start = 6.dp)) {
            var lastLayer = -1
            byScholar.take(shown).forEach { (sch, pts) ->
                val p0 = pts.first()
                if (p0.layer != lastLayer) { lastLayer = p0.layer; Spacer(Modifier.height(4.dp)); Text(SharhPoint.layerLabel(p0.layer), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary) }
                ScholarBlock(sch, p0.death, p0.isAthar, p0.layer, pts, onOpenSection)
            }
            if (byScholar.size > shown) TextButton(onClick = { shown += 12 }) { Text("اعرض مزيدًا من العلماء (${ar(byScholar.size - shown)})") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** شرح الحديث نفسه عند شارح واحد، نقاطًا مرقَّمة من نصّ شرحه (تُحمَّل عند الفتح) */
@Composable
fun SharhUnitBlock(vm: MuhaddithViewModel, unit: PassageRef, onOpenSection: (Long) -> Unit) {
    var open by rememberSaveable("u" + unit.sectionId) { mutableStateOf(false) }
    var pts by remember("u" + unit.sectionId) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(open) { if (open && pts == null) pts = vm.unitPoints(unit.sectionId) }
    val d = unit.death?.let { " (ت ${ar(it)} هـ)" } ?: ""
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("شرح " + unit.author + d, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(unit.bookTitle + listOfNotNull(unit.vol?.let { " ج${ar(it)}" }, unit.page?.let { " ص${ar(it)}" }).joinToString("") + " · ثقة الربط ${ar((unit.conf * 100).toInt())}٪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                val list = pts
                if (list == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (list.isEmpty()) Text("لا نقاط في هذا الموضع (اقتباس فقط) — افتح الموضع.", style = MaterialTheme.typography.bodySmall)
                else list.forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("${ar(i + 1)}. ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                        Text(display(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { onOpenSection(unit.sectionId) }) { Text("افتح الموضع كاملًا") }
            }
        }
    }
}

@Composable
fun PointsSection(points: List<SharhPoint>?, sections: List<PassageRef>?, installed: Boolean, onOpenSection: (Long) -> Unit, onOpenData: () -> Unit, outdated: Boolean = false, vm: MuhaddithViewModel? = null) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel("شروح العلماء واستنباطاتهم من الحديث" + (points?.let { " (${ar(it.size)})" } ?: ""))
            if (!installed) {
                Text(if (outdated) "حزمة الشروح المثبَّتة من إصدار قديم — حدّثها من «إدارة حزم البيانات» لتظهر الآثار والمصادر المرقَّمة." else "ثبّت حزمة «شروح الحديث وأسباب وروده» لتظهر هنا استنباطات العلماء من الصحابة إلى المعاصرين مصنَّفةً ومسنَدةً إلى مصادرها.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenData) { Text("إدارة حزم البيانات") }
                return@Column
            }
            if (points == null) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); return@Column }
            Text("كل قسم مطويّ؛ اضغط عنوانه ليُفتح. العلماء بترتيب الوفاة من الصحابة إلى المعاصرين، وتحت كل عالم نقاطه مرقَّمة ١ ٢ ٣ مع مصدرها؛ شرح ألفاظ المتن («قوله «…»») أولًا ثم آثاره وفوائده. الأثر الواحد يُذكر مرة واحدة وتحته كل من رواه.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // ٠) الشرح المتسلسل للمتن: كل النقاط (آثار، شرح ألفاظ، فوائد، منقولات) بترتيب وفاة أصحابها
            if (points.isNotEmpty()) PointGroup("شرح المتن متسلسلًا: من الصحابة إلى المعاصرين", points, onOpenSection)
            // ١) شرح الحديث نفسه عند الشرّاح — نقاطًا من نصّ كل شارح
            val main = sections?.filter { it.primary }.orEmpty().sortedWith(compareBy({ it.death ?: 99999 }, { it.author }, { it.bookTitle }))
            if (main.isNotEmpty() && vm != null) {
                var open by rememberSaveable { mutableStateOf(false) }
                Surface(onClick = { open = !open }, shape = RoundedCornerShape(10.dp), color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("شرح الحديث نفسه عند الشرّاح — نقاطًا (${ar(main.size)} موضعًا)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (open) {
                    Text("كل موضع وحدة شرح مخصّصة لهذا الحديث بعينه في كتاب الشارح، بترتيب وفاة الشرّاح؛ اضغط الشارح لتُعرض نقاطه من نصّ شرحه.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var shown by rememberSaveable { mutableIntStateOf(10) }
                    main.take(shown).forEach { u -> SharhUnitBlock(vm, u, onOpenSection) }
                    if (main.size > shown) TextButton(onClick = { shown += 15 }) { Text("اعرض مزيدًا من الشرّاح (${ar(main.size - shown)})") }
                }
            }
            if (points.isEmpty() && main.isEmpty()) {
                Text("لم يُعثر على استنباطات مربوطة بهذا الحديث بدرجة الدقة الحالية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // ٢) آثار الصحابة والتابعين، ثم ٣) أصناف الأقوال المنقولة — كلها مطوية
                val groups = ArrayList<Pair<String, List<SharhPoint>>>()
                points.filter { it.isLemma }.takeIf { it.isNotEmpty() }?.let { groups.add("شرح ألفاظ المتن (قوله «…»)" to it) }
                points.filter { it.isAthar }.takeIf { it.isNotEmpty() }?.let { groups.add("آثار الصحابة والتابعين" to it) }
                SharhPoint.CATEGORIES.forEach { c -> points.filter { !it.isAthar && !it.isLemma && it.category == c }.takeIf { it.isNotEmpty() }?.let { groups.add("بحسب الموضوع: $c" to it) } }
                points.filter { !it.isAthar && !it.isLemma && it.category !in SharhPoint.CATEGORIES }.takeIf { it.isNotEmpty() }?.let { groups.add("بحسب الموضوع: أخرى" to it) }
                groups.forEach { (title, list) -> PointGroup(title, list, onOpenSection) }
            }
            val passing = sections?.filter { !it.primary }.orEmpty()
            if (passing.isNotEmpty()) {
                var open2 by rememberSaveable { mutableStateOf(false) }
                FoldHeader("ذُكر عرضًا في شرح أحاديث أخرى", open2, { open2 = !open2 }, count = passing.size)
                if (open2) passing.forEach { p -> PassageRow(p) { onOpenSection(p.sectionId) } }
            }
        }
    }
}

/* ====================== ٣) قارئ مقاطع الشروح ====================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShSectionScreen(vm: MuhaddithViewModel, sectionId: Long, onBack: () -> Unit, onOpenSection: (Long) -> Unit, onOpenHadith: (Long) -> Unit) {
    val ctx = LocalContext.current
    val sec = remember(sectionId) { vm.shuruh.section(sectionId) }
    val book = remember(sectionId) { sec?.let { vm.shuruh.book(it.bookId) } }
    var linked by remember(sectionId) { mutableStateOf<List<Hadith>?>(null) }
    LaunchedEffect(sectionId) { linked = withContext(Dispatchers.IO) { vm.shuruh.sectionClusters(sectionId).mapNotNull { vm.firstOfCluster(it) } } }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(book?.title ?: "", maxLines = 1, style = MaterialTheme.typography.titleMedium); sec?.let { Text(it.ref, style = MaterialTheme.typography.labelSmall) } } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = {
                IconButton(onClick = {
                    sec?.let {
                        val text = "${book?.title ?: ""} — ${book?.author ?: ""} ${it.ref}\n${it.title ?: ""}\n\n${it.text}\n\n— المحدِّث"
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
                        ctx.startActivity(android.content.Intent.createChooser(i, "مشاركة"))
                    }
                }) { Icon(Icons.Default.Share, contentDescription = "مشاركة") }
            })
    }) { padding ->
        if (sec == null) { EmptyState("المقطع غير موجود", "", Modifier.padding(padding)); return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sec.title?.takeIf { it.isNotBlank() }?.let { Text(display(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sec.text.split('\n').forEach { p -> if (p.isNotBlank()) Text(display(p), style = MatnStyle, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val prev = remember(sectionId) { vm.shuruh.neighbor(sec, next = false) }
                val next = remember(sectionId) { vm.shuruh.neighbor(sec, next = true) }
                TextButton(enabled = prev != null, onClick = { prev?.let(onOpenSection) }) { Text("‹ السابق") }
                TextButton(enabled = next != null, onClick = { next?.let(onOpenSection) }) { Text("التالي ›") }
            }
            linked?.takeIf { it.isNotEmpty() }?.let { l ->
                Column { SectionLabel("الأحاديث المشروحة في هذا المقطع (${ArabicText.arabicDigits(l.size)})"); l.forEach { h -> HadithRow(h) { onOpenHadith(h.id) } } }
            }
            Text(listOfNotNull(book?.let { "${it.title} — ${it.author}" + (it.death?.let { d -> " (ت ${ArabicText.arabicDigits(d)} هـ)" } ?: "") }, book?.editor?.let { "تحقيق $it" }, book?.publisher,
                if (book?.kind == "modern") "النص OCR من مصوَّرات المكتبة الوقفية" else "النص من مكتبة OpenITI").joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** فهرس كتاب شرح */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShBookScreen(vm: MuhaddithViewModel, bookId: Long, onBack: () -> Unit, onOpenSection: (Long) -> Unit) {
    val book = remember(bookId) { vm.shuruh.book(bookId) }
    var sections by remember(bookId) { mutableStateOf<List<MkSection>>(emptyList()) }
    var pageQuery by remember { mutableStateOf("") }
    LaunchedEffect(bookId) { sections = withContext(Dispatchers.IO) { vm.shuruh.sections(bookId, 0, 20000) } }
    Scaffold(topBar = { TopAppBar(title = { Text(book?.title ?: "", maxLines = 1) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } }) }) { padding ->
        if (book == null) { EmptyState("الكتاب غير موجود", "", Modifier.padding(padding)); return@Scaffold }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(listOfNotNull(book.author, book.death?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }, "${ArabicText.arabicDigits(book.sections)} مقطعًا").joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pageQuery, onValueChange = { pageQuery = it.filter { c -> c.isDigit() || c == '/' } }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("انتقل إلى صفحة (ج/ص)") })
                    TextButton(onClick = {
                        val parts = pageQuery.split('/'); val vol = if (parts.size > 1) parts[0].toIntOrNull() else null; val page = parts.last().toIntOrNull()
                        if (page != null) vm.shuruh.sectionAtPage(bookId, vol, page)?.let(onOpenSection)
                    }) { Text("اذهب") }
                }
            }
            items(sections, key = { it.id }) { s ->
                Surface(onClick = { onOpenSection(s.id) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(display(s.title?.takeIf { it.isNotBlank() } ?: "مقطع ${ArabicText.arabicDigits(s.ord + 1)}"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                        Text(s.ref, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

package org.murabbie.muhaddith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.*
import org.murabbie.muhaddith.search.ArabicText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* =========================================================================================
   ١) أجزاء تُعرض داخل صفحة الحديث: أحكام العلماء، ومواضع الكلام عليه في كتب مختلف الحديث
   ========================================================================================= */

/** لون درجة الحكم بحسب مستواها الرقمي (٠ صحيح … ٥ موضوع) */
@Composable
fun levelColor(level: Int?): androidx.compose.ui.graphics.Color = when (level) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.secondary
    null -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

private fun originLabel(o: String) = when (o) {
    "text" -> "من نص الكتاب"
    "api" -> "من طبعة محقَّقة"
    "bulugh" -> "بلوغ المرام"
    "passage" -> "من كتب التخريج والعلل"
    "albani" -> "من كتب الألباني (نص OCR)"
    else -> o
}

@Composable
fun RulingCard(r: Ruling, onOpenSection: ((Long) -> Unit)?, onOpenHadith: ((String) -> Unit)?) {
    var expanded by remember(r.id) { mutableStateOf(false) }
    val color = levelColor(r.level)
    Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.scholar, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    r.death?.let { Text("ت ${ArabicText.arabicDigits(it)} هـ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Box(Modifier.background(color.copy(alpha = 0.13f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(display(r.grade), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(listOfNotNull(r.source, r.ref?.takeIf { it.isNotBlank() }?.let { ArabicText.arabicDigits(it) }).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (!(r.bhid != null && r.conf >= 0.99)) {
                val cc = if (r.conf >= 0.85) MaterialTheme.colorScheme.onSurfaceVariant else if (r.conf >= 0.65) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                Text("ربط: ${r.confLabel} (${ArabicText.arabicDigits((r.conf * 100).toInt())}٪)", style = MaterialTheme.typography.labelSmall, color = cc)
            }
            if (expanded) {
                r.quote?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(display(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(originLabel(r.origin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (r.sectionId != null && onOpenSection != null) TextButton(onClick = { onOpenSection(r.sectionId) }) { Text("افتح الموضع") }
                    val target = r.bhid ?: r.viaBhid
                    if (target != null && onOpenHadith != null) TextButton(onClick = { onOpenHadith(target) }) { Text("الرواية") }
                }
            }
        }
    }
}

/** ملخص الأحكام: عدد من صحّح/حسّن/ضعّف */
@Composable
fun RulingsSummary(all: List<Ruling>) {
    val byLevel = all.groupBy { when (it.level) { 0 -> 0; 1 -> 1; null -> -1; else -> 2 } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "صحّحه", 1 to "حسّنه", 2 to "ضعّفه").forEach { (lv, label) ->
            val n = byLevel[lv]?.map { it.scholar }?.distinct()?.size ?: 0
            if (n > 0) {
                val c = levelColor(lv)
                Box(Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("$label ${ArabicText.arabicDigits(n)}", style = MaterialTheme.typography.labelMedium, color = c)
                }
            }
        }
    }
}

@Composable
fun RulingsSection(bundle: RulingsBundle?, installed: Boolean, precision: Int = RulingPrecision.PRECISE, onOpenSection: (Long) -> Unit, onOpenHadith: (String) -> Unit, onOpenData: () -> Unit, onPrecision: ((Int) -> Unit)? = null) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            var openSec by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clickable { openSec = !openSec }) { SectionLabel("أحكام العلماء على الحديث" + (bundle?.let { " (${ArabicText.arabicDigits(it.total)})" } ?: "") + (if (openSec) " ▲" else " ▼")) }
                if (installed && onPrecision != null) {
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = true }) { Text("الدقة: ${RulingPrecision.label(precision)}", style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        listOf(RulingPrecision.STRICT, RulingPrecision.PRECISE, RulingPrecision.WIDE).forEach { p ->
                            DropdownMenuItem(text = { Column { Text(RulingPrecision.label(p)); Text(RulingPrecision.description(p), style = MaterialTheme.typography.labelSmall) } },
                                onClick = { onPrecision(p); open = false })
                        }
                    }
                }
            }
            if (installed && !openSec) {
                // مطويّ: خلاصة الأحكام فقط
                if (bundle != null && !bundle.isEmpty) RulingsSummary(bundle.own + bundle.others)
                return@Column
            }
            when {
                !installed -> {
                    Text("ثبّت حزمة «أحكام العلماء ومختلف الحديث» لتظهر هنا أحكام الأئمة والمحققين مع مصادرها.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenData) { Text("إدارة حزم البيانات") }
                }
                bundle == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                bundle.isEmpty -> Text("لم يُعثر في الحزمة على حكم منصوص لهذا الحديث ولا لطرقه.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    RulingsSummary(bundle.own + bundle.others)
                    Spacer(Modifier.height(8.dp))
                    var showAllOthers by remember { mutableStateOf(false) }
                    if (bundle.own.isNotEmpty()) {
                        Text("على هذه الرواية بعينها (${ArabicText.arabicDigits(bundle.own.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        bundle.own.forEach { RulingCard(it, onOpenSection, onOpenHadith) }
                    }
                    if (bundle.others.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("على الحديث وطرقه الأخرى (${ArabicText.arabicDigits(bundle.others.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        Text("الحكم هنا على طريق أخرى للحديث المجمَّع لا على هذه الرواية بعينها، ومعه درجة ثقة الربط النصي؛ افتح الحكم لترى موضعه ونصّه.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val shown = if (showAllOthers) bundle.others else bundle.others.take(12)
                        shown.forEach { RulingCard(it, onOpenSection, onOpenHadith) }
                        if (bundle.others.size > 12 && !showAllOthers) TextButton(onClick = { showAllOthers = true }) { Text("اعرض الكل (${ArabicText.arabicDigits(bundle.others.size)})") }
                    }
                }
            }
        }
    }
}

@Composable
fun PassagesSection(passages: List<PassageRef>?, installed: Boolean, onOpenSection: (Long) -> Unit) {
    if (!installed) return
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            var open by rememberSaveable { mutableStateOf(false) }
            FoldHeader("مختلف الحديث والتخريج وكتب الألباني: مواضع ذكر الحديث", open, { open = !open }, count = passages?.size)
            if (!open) return@Column
            when {
                passages == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                passages.isEmpty() -> Text("لم يُعثر على موضع يُذكر فيه هذا الحديث في كتب الحزمة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val groups = passages.groupBy { it.kind }.toSortedMap(compareBy { MkKinds.ORDER.indexOf(it) })
                    groups.forEach { (kind, list) ->
                        Spacer(Modifier.height(4.dp))
                        Text(MkKinds.label(kind) + " (${ArabicText.arabicDigits(list.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        list.forEach { p -> PassageRow(p) { onOpenSection(p.sectionId) } }
                    }
                }
            }
        }
    }
}

@Composable
fun PassageRow(p: PassageRef, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.bookTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(listOfNotNull(p.vol?.let { "ج${ArabicText.arabicDigits(it)}" }, p.page?.let { "ص${ArabicText.arabicDigits(it)}" }).joinToString(" "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(p.author + " · ثقة الربط ${ArabicText.arabicDigits((p.conf * 100).toInt())}٪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.title?.takeIf { it.isNotBlank() }?.let { Text(display(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2) }
            p.snippet?.let { Spacer(Modifier.height(3.dp)); Text(display(it), style = MaterialTheme.typography.bodySmall, maxLines = 4) }
        }
    }
}

/* =========================================================================================
   ٢) تصفّح كتب مختلف الحديث والتخريج والبحث فيها
   ========================================================================================= */

@Composable
fun MkHome(vm: MuhaddithViewModel, state: UiState, onOpenBook: (Long) -> Unit, onOpenSection: (Long) -> Unit, onOpenScholars: () -> Unit, onOpenData: () -> Unit, onOpenShBook: (Long) -> Unit = {}) {
    val info = state.ahkam
    val shBooks = remember(state.shuruh) { if (state.shuruh.installed) vm.shuruh.books() else emptyList() }
    if (!info.installed && shBooks.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("حزمة أحكام العلماء ومختلف الحديث غير مثبَّتة",
                "تحوي أحكام الأئمة والمحققين على الأحاديث مع مصادرها، وكتب مختلف الحديث والناسخ والمنسوخ وأصول الجمع بين الأحاديث والتخريج والعلل كاملةً، مربوطةً بأحاديث المحدِّث.")
            Button(onClick = onOpenData) { Text("إدارة حزم البيانات") }
        }
        return
    }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PassageHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val books = remember(info) { if (info.installed) vm.ahkam.books() else emptyList() }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = null; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(350)
        hits = withContext(Dispatchers.IO) { vm.ahkam.searchPassages(query) }
        searching = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info.installed) item {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ابحث في كتب مختلف الحديث والتخريج والألباني…") },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
        }
        if (hits != null) {
            item { Text("نتائج البحث (${ArabicText.arabicDigits(hits!!.size)})", style = MaterialTheme.typography.titleMedium) }
            if (hits!!.isEmpty()) item { EmptyState("لا نتائج", "جرّب ألفاظًا أخرى، أو ضع العبارة بين علامتي تنصيص للبحث عنها كما هي.") }
            items(hits!!, key = { it.section.id }) { h ->
                Surface(onClick = { onOpenSection(h.section.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row { Text(h.bookTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)); Text(h.section.ref, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        h.section.title?.takeIf { it.isNotBlank() }?.let { Text(display(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2) }
                        Spacer(Modifier.height(3.dp))
                        HighlightedText(display(h.snippet), ArabicText.tokens(query), MaterialTheme.typography.bodySmall)
                    }
                }
            }
            return@LazyColumn
        }
        if (shBooks.isNotEmpty()) {
            val sh = state.shuruh
            item { Text("شروح الحديث وأسباب وروده", style = MaterialTheme.typography.titleMedium); Text("${ArabicText.arabicDigits(sh.books)} كتابًا · ${ArabicText.arabicDigits(sh.points)} استنباطًا لـ${ArabicText.arabicDigits(sh.scholars)} عالمًا على ${ArabicText.arabicDigits(sh.clusters)} حديثًا", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            shBooks.groupBy { it.kind }.toSortedMap(compareBy { MkKinds.ORDER.indexOf(it) }).forEach { (kind, list) ->
                item { Text(MkKinds.label(kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary) }
                items(list, key = { "sh" + it.id }) { b ->
                    Surface(onClick = { onOpenShBook(b.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(b.author, b.death?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }, "${ArabicText.arabicDigits(b.sections)} مقطعًا").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (info.installed) item {
            Surface(onClick = onOpenScholars, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("العلماء وأحكامهم", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${ArabicText.arabicDigits(info.scholars)} عالمًا · ${ArabicText.arabicDigits(info.rulings)} حكمًا على ${ArabicText.arabicDigits(info.clusters)} حديثًا مجمَّعًا",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        val grouped = books.groupBy { it.kind }.toSortedMap(compareBy { MkKinds.ORDER.indexOf(it) })
        grouped.forEach { (kind, list) ->
            item { Spacer(Modifier.height(6.dp)); Text(MkKinds.label(kind), style = MaterialTheme.typography.titleMedium) }
            items(list, key = { it.id }) { b ->
                Surface(onClick = { onOpenBook(b.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(3.dp))
                        Text(listOfNotNull(b.author, b.death?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }, "${ArabicText.arabicDigits(b.sections)} مقطعًا",
                            "${ArabicText.arabicDigits(b.words / 1000)} ألف كلمة").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text("النصوص من مكتبة OpenITI المفتوحة (طبعات الشاملة)، والأحكام مستخرَجة آليًّا من نصوص الكتب ومقابَلةٌ بمصادرها؛ فارجع إلى الموضع المذكور للتثبّت.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MkBookScreen(vm: MuhaddithViewModel, bookId: Long, onBack: () -> Unit, onOpenSection: (Long) -> Unit) {
    val book = remember(bookId) { vm.ahkam.book(bookId) }
    var sections by remember(bookId) { mutableStateOf<List<MkSection>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PassageHit>?>(null) }
    var pageQuery by remember { mutableStateOf("") }
    LaunchedEffect(bookId) { sections = withContext(Dispatchers.IO) { vm.ahkam.sections(bookId, 0, 5000) } }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        hits = withContext(Dispatchers.IO) { vm.ahkam.searchPassages(query, bookId) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(book?.title ?: "", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
    }) { padding ->
        if (book == null) { EmptyState("الكتاب غير موجود", "", Modifier.padding(padding)); return@Scaffold }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(listOfNotNull(book.author, book.death?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                listOfNotNull(book.editor?.let { "تحقيق: $it" }, book.publisher, book.edition).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("ابحث في هذا الكتاب") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pageQuery, onValueChange = { pageQuery = it.filter { c -> c.isDigit() } }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("انتقل إلى صفحة (ج/ص مثل 3/120 أو 120)") })
                    TextButton(onClick = {
                        val parts = pageQuery.split('/'); val vol = if (parts.size > 1) parts[0].toIntOrNull() else null; val page = parts.last().toIntOrNull()
                        if (page != null) vm.ahkam.sectionAtPage(bookId, vol, page)?.let(onOpenSection)
                    }) { Text("اذهب") }
                }
            }
            val h = hits
            if (h != null) {
                item { Text("نتائج (${ArabicText.arabicDigits(h.size)})", style = MaterialTheme.typography.titleSmall) }
                items(h, key = { it.section.id }) { hit ->
                    Surface(onClick = { onOpenSection(hit.section.id) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row { Text(display(hit.section.title ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2); Text(hit.section.ref, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            HighlightedText(display(hit.snippet), ArabicText.tokens(query), MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item { Text("الفهرس (${ArabicText.arabicDigits(sections.size)} مقطعًا)", style = MaterialTheme.typography.titleSmall) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(vm: MuhaddithViewModel, sectionId: Long, onBack: () -> Unit, onOpenSection: (Long) -> Unit, onOpenHadith: (Long) -> Unit) {
    val ctx = LocalContext.current
    val sec = remember(sectionId) { vm.ahkam.section(sectionId) }
    val book = remember(sectionId) { sec?.let { vm.ahkam.book(it.bookId) } }
    var quoted by remember(sectionId) { mutableStateOf<List<Hadith>?>(null) }
    LaunchedEffect(sectionId) {
        quoted = withContext(Dispatchers.IO) { vm.ahkam.sectionHadiths(sectionId).mapNotNull { vm.hadithByBhid(it.first) }.distinctBy { it.clusterId ?: it.id } }
    }
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
                val prev = remember(sectionId) { vm.ahkam.neighbor(sec, next = false) }
                val next = remember(sectionId) { vm.ahkam.neighbor(sec, next = true) }
                TextButton(enabled = prev != null, onClick = { prev?.let(onOpenSection) }) { Text("‹ السابق") }
                TextButton(enabled = next != null, onClick = { next?.let(onOpenSection) }) { Text("التالي ›") }
            }
            val q = quoted
            if (q != null && q.isNotEmpty()) {
                Column {
                    SectionLabel("الأحاديث المذكورة في هذا المقطع (${ArabicText.arabicDigits(q.size)})")
                    q.forEach { h -> HadithRow(h) { onOpenHadith(h.id) } }
                }
            }
            Text(listOfNotNull(book?.let { "${it.title} — ${it.author}" }, book?.editor?.let { "تحقيق $it" }, book?.publisher, "النص من مكتبة OpenITI").joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ============================== ٣) العلماء وأحكامهم ============================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarsScreen(vm: MuhaddithViewModel, onBack: () -> Unit, onOpenScholar: (Scholar) -> Unit) {
    val scholars = remember { vm.ahkam.scholars() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, scholars) { val n = ArabicText.normalize(query); if (n.isEmpty()) scholars else scholars.filter { ArabicText.normalize(it.name).contains(n) } }
    Scaffold(topBar = { TopAppBar(title = { Text("العلماء وأحكامهم") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("اسم العالم") }) }
            item { Text("${ArabicText.arabicDigits(filtered.size)} عالمًا مرتَّبين بالوفاة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { it.id }) { s ->
                Surface(onClick = { onOpenScholar(s) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(s.death?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }, if (s.kind == "modern") "محقق معاصر" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${ArabicText.arabicDigits(s.rulings)} حكمًا", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarScreen(vm: MuhaddithViewModel, scholar: Scholar, onBack: () -> Unit, onOpenSection: (Long) -> Unit, onOpenHadith: (Long) -> Unit) {
    var items by remember(scholar.id) { mutableStateOf<List<Ruling>>(emptyList()) }
    var page by remember(scholar.id) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(scholar.id, page) {
        loading = true
        val more = withContext(Dispatchers.IO) { vm.ahkam.rulingsOfScholar(scholar.id, page * 50) }
        items = items + more; loading = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(scholar.name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
            item { Text("${ArabicText.arabicDigits(scholar.rulings)} حكمًا في الحزمة" + (scholar.death?.let { " · ت ${ArabicText.arabicDigits(it)} هـ" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.id }) { r ->
                RulingCard(r, onOpenSection) { bhid -> vm.hadithByBhid(bhid)?.let { onOpenHadith(it.id) } }
            }
            if (items.size < scholar.rulings) item {
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp)) else TextButton(onClick = { page++ }) { Text("المزيد") }
            }
        }
    }
}

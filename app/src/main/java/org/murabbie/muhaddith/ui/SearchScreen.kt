package org.murabbie.muhaddith.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Engine
import org.murabbie.muhaddith.data.Hokm
import org.murabbie.muhaddith.data.SearchHit
import org.murabbie.muhaddith.data.SearchScope
import org.murabbie.muhaddith.search.ArabicText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleEngine: (Engine) -> Unit,
    onScope: (SearchScope) -> Unit,
    onBookFilter: (Long?) -> Unit,
    onHokmFilter: (Int?) -> Unit,
    onOpenHadith: (Long) -> Unit,
    onSaveSearch: (String) -> Unit,
    onOpenData: () -> Unit,
    onExport: () -> Unit = {},
    onBookScope: (Set<Long>) -> Unit = {},
    onUpdate: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilters by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showBookPicker by remember { mutableStateOf(false) }
    var showScopeDialog by remember { mutableStateOf(false) }
    val bookScope = state.settings.bookScope
    val groups = remember(state.books) { BookGroups.of(state.books) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ابحث في المتون… (\"عبارة\"، -استبعاد)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch() })
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EngineChip(Engine.LITERAL, Engine.LITERAL in state.engines) { onToggleEngine(Engine.LITERAL) }
                    EngineChip(Engine.MORPH, Engine.MORPH in state.engines) { onToggleEngine(Engine.MORPH) }
                    if (state.semanticReady) EngineChip(Engine.SEMANTIC, Engine.SEMANTIC in state.engines) { onToggleEngine(Engine.SEMANTIC) }
                    else AssistChip(onClick = onOpenData, label = { Text("دلالي — ثبّت حزمته") })
                    VerticalDivider(Modifier.height(24.dp))
                    AssistChip(onClick = { showFilters = !showFilters },
                        label = { Text(if (showFilters) "إخفاء المرشّحات" else "المرشّحات") })
                    if (state.bookFilter != null) {
                        val b = state.books.firstOrNull { it.id == state.bookFilter }
                        InputChip(selected = true, onClick = { onBookFilter(null) },
                            label = { Text(b?.title ?: "كتاب") },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                    if (bookScope.isNotEmpty() && state.bookFilter == null) {
                        val gname = groups.firstOrNull { it.second == bookScope }?.first
                        InputChip(selected = true, onClick = { onBookScope(emptySet()) },
                            label = { Text(gname ?: "نطاق: ${ArabicText.arabicDigits(bookScope.size)} كتب") },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                    if (state.hokmFilter != null) {
                        InputChip(selected = true, onClick = { onHokmFilter(null) },
                            label = { Text(Hokm.label(state.hokmFilter) ?: "") },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                }
                if (showFilters) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("نطاق البحث")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchScope.entries.forEach { s ->
                            FilterChip(selected = state.scope == s, onClick = { onScope(s) }, label = { Text(s.label) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("الكتاب")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = state.bookFilter == null, onClick = { onBookFilter(null) }, label = { Text("كل الكتب") })
                        AssistChip(onClick = { showBookPicker = true }, label = { Text("اختر كتابًا…") })
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("نطاق الكتب (يبقى محفوظًا لكل بحث)")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = bookScope.isEmpty(), onClick = { onBookScope(emptySet()) }, label = { Text("كل الكتب") })
                        for ((label, ids) in groups) if (ids.isNotEmpty() && ids.size < state.books.size)
                            FilterChip(selected = bookScope == ids, onClick = { onBookScope(if (bookScope == ids) emptySet() else ids) }, label = { Text(label) })
                        AssistChip(onClick = { showScopeDialog = true }, label = { Text("عدة كتب…") })
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("الحكم")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hokm.filterable.forEach { (code, label) ->
                            FilterChip(selected = state.hokmFilter == code,
                                onClick = { onHokmFilter(if (state.hokmFilter == code) null else code) },
                                label = { Text(label) })
                        }
                    }
                }
            }
        }

        when {
            state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.searched -> WelcomePane(state, onQueryChange, onSearch, onOpenData, onUpdate)
            state.results.isEmpty() -> EmptyState("لا نتائج",
                "جرّب تفعيل البحث الصرفي، أو وسّع النطاق إلى المتن والإسناد، أو أزل المرشّحات.")
            else -> ResultsList(state, onOpenHadith, onExport) { showSaveDialog = true }
        }
    }

    if (showScopeDialog) BookScopeDialog(state.books, bookScope, onSave = { onBookScope(it); showScopeDialog = false }, onDismiss = { showScopeDialog = false })
    if (showBookPicker) {
        BookPickerDialog(state.books, onPick = { onBookFilter(it); showBookPicker = false }, onDismiss = { showBookPicker = false })
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf(state.query.take(40)) }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("حفظ خطة البحث") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الخطة") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSaveSearch(name.trim()); showSaveDialog = false }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun BookPickerDialog(books: List<org.murabbie.muhaddith.data.Book>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(q, books) {
        val n = ArabicText.normalize(q)
        if (n.isEmpty()) books.take(200)
        else books.filter { ArabicText.normalize(it.title).contains(n) || ArabicText.normalize(it.author ?: "").contains(n) }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر كتابًا") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("اسم الكتاب أو مؤلفه") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.id }) { b ->
                        Surface(onClick = { onPick(b.id) }, shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium)
                                Text("${b.author ?: ""} · ${ArabicText.arabicDigits(b.hadithCount)} حديثًا",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
private fun WelcomePane(state: UiState, onQueryChange: (String) -> Unit, onSearch: () -> Unit, onOpenData: () -> Unit, onUpdate: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val d = state.dataset
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(ArabicText.arabicDigits(d?.hadiths ?: 0), "رواية", Modifier.weight(1f))
                StatTile(ArabicText.arabicDigits(d?.books ?: 0), "كتاب", Modifier.weight(1f))
                StatTile(ArabicText.arabicDigits(d?.rawis ?: 0), "راوٍ", Modifier.weight(1f))
            }
        }
        state.updateAvailable?.let { u ->
            item {
                Surface(onClick = onUpdate, color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("تحديث متاح: الإصدار ${ArabicText.arabicDigits(u.versionName)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(if (state.updateFile != null) "نُزّل — اضغط للتثبيت" else if (state.pack.busy) "جارٍ التنزيل…" else "اضغط للتنزيل من الخادم", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        }
        if (state.dataset?.isEmpty == true) {
            item {
                Surface(onClick = onOpenData, color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("لا بيانات بعد", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("فعّل حزمة الكتب التسعة أو الحزمة الكاملة من تبويب «البيانات» — استيرادًا من ملفات على الهاتف أو تنزيلًا من رابط. بعدها يعمل كل شيء بلا إنترنت.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        } else if (state.dataset?.isFull == false) {
            item {
                Surface(onClick = onOpenData, color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("الحزمة الحالية: الكتب التسعة. لتفعيل الكتب الألف والأربعمئة كاملةً افتح «البيانات».",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item { SectionLabel("عمليات بحث سابقة") }
            items(state.history) { h -> ClickableRow(h) { onQueryChange(h); onSearch() } }
        }
        item { SectionLabel("جرّب") }
        items(listOf("النية", "\"إنما الأعمال بالنيات\"", "الرفق", "غض البصر", "المسح على الخفين", "الصيام -رمضان")) { s ->
            ClickableRow(s) { onQueryChange(s); onSearch() }
        }
    }
}

@Composable
private fun ResultsList(state: UiState, onOpen: (Long) -> Unit, onExport: () -> Unit, onSaveSearch: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${ArabicText.arabicDigits(state.results.size)} نتيجة" + if (state.results.size >= 200) " (الأعلى)" else "",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onExport) { Text("تصدير Word/PDF") }
                TextButton(onClick = onSaveSearch) { Text("احفظ الخطة") }
            }
        }
        if (state.expansion.any { it.roots.isNotEmpty() }) item { ExpansionCard(state) }
        items(state.results, key = { it.hadith.id }) { hit -> ResultCard(hit, state.highlight) { onOpen(hit.hadith.id) } }
    }
}

@Composable
private fun ExpansionCard(state: UiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("التوسيع الصرفي", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            state.expansion.forEach { t ->
                if (t.roots.isNotEmpty()) {
                    Text("«${t.original}» → " + t.roots.joinToString("، ") + if (t.negated) " (مستبعد)" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(hit: SearchHit, terms: List<String>, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.hadith.bookTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                hit.hadith.hadithNum?.let {
                    Text("(${ArabicText.arabicDigits(it)})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                GradePill(hit.hadith.hokmLabel)
            }
            Spacer(Modifier.height(8.dp))
            val disp = LocalDisplay.current
            if (disp.showIsnadInResults && !hit.hadith.sanad.isNullOrBlank()) {
                Text(display(hit.hadith.sanad).take(90) + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            val shown = display(hit.snippet)
            Text(if (disp.highlight) highlight(shown, terms, MaterialTheme.colorScheme.primary) else androidx.compose.ui.text.AnnotatedString(shown), style = MatnStyle)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                hit.engines.forEach { EngineBadge(it) }
                hit.rulingSummary?.let {
                    Spacer(Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

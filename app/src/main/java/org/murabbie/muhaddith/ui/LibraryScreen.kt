package org.murabbie.muhaddith.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Narrator
import org.murabbie.muhaddith.search.ArabicText

/** المجموعات وخطط البحث المحفوظة */
@Composable
fun CollectionsScreen(
    vm: MuhaddithViewModel, state: UiState,
    onOpenHadith: (Long) -> Unit,
    onRunSaved: (org.murabbie.muhaddith.data.Repository.SavedSearch) -> Unit,
    onExport: (String, List<Long>) -> Unit = { _, _ -> }
) {
    var openCollection by remember { mutableStateOf<Long?>(null) }
    var showNew by remember { mutableStateOf(false) }

    val current = openCollection
    if (current != null) {
        val items = remember(current, state.collections) { vm.collectionItems(current) }
        val name = state.collections.firstOrNull { it.id == current }?.name ?: ""
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openCollection = null }) { Text("‹ المجموعات") }
                    Spacer(Modifier.weight(1f))
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = items.isNotEmpty(), onClick = { onExport(name, items.map { it.id }) }) { Text("تصدير") }
                    TextButton(onClick = { vm.deleteCollection(current); openCollection = null }) { Text("حذف") }
                }
            }
            if (items.isEmpty()) item { EmptyState("المجموعة فارغة", "أضف أحاديث إليها من صفحة الحديث (أيقونة الحفظ).") }
            items(items, key = { it.id }) { h -> HadithRow(h) { onOpenHadith(h.id) } }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("مجموعاتي", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showNew = true }) { Text("جديدة +") }
            }
        }
        if (state.collections.isEmpty()) item { EmptyState("لا مجموعات بعد", "أنشئ مجموعة لتحفظ فيها ما ينفعك من الأحاديث.") }
        items(state.collections, key = { it.id }) { c ->
            Surface(onClick = { openCollection = c.id }, shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${ArabicText.arabicDigits(c.itemCount)} حديثًا", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)); Text("خطط بحث محفوظة", style = MaterialTheme.typography.titleMedium) }
        if (state.savedSearches.isEmpty()) {
            item { Text("احفظ خطط بحثك المركّبة من صفحة النتائج لتعود إليها لاحقًا.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.savedSearches, key = { it.id }) { s ->
            Surface(onClick = { onRunSaved(s) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.query + " · " + s.engines.joinToString("، ") { it.label } + " · " + s.scope.label,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.deleteSavedSearch(s.id) }) { Text("حذف") }
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNew = false }, title = { Text("مجموعة جديدة") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.createCollection(name.trim()); showNew = false }) { Text("إنشاء") } },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("إلغاء") } })
    }
}

/** فهرس الكتب والرواة */
@Composable
fun LibraryScreen(vm: MuhaddithViewModel, state: UiState, onOpenBook: (Long) -> Unit, onOpenRawi: (Long) -> Unit,
                  onOpenMkBook: (Long) -> Unit = {}, onOpenSection: (Long) -> Unit = {}, onOpenScholars: () -> Unit = {}, onOpenData: () -> Unit = {},
                  onOpenShBook: (Long) -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    var bookQuery by remember { mutableStateOf("") }
    var rawiQuery by remember { mutableStateOf("") }
    var rawis by remember { mutableStateOf<List<Narrator>>(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الكتب (${ArabicText.arabicDigits(state.books.size)})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("الرواة") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("الشروح والأحكام") })
        }
        if (tab == 2) {
            MkHome(vm, state, onOpenBook = onOpenMkBook, onOpenSection = onOpenSection, onOpenScholars = onOpenScholars, onOpenData = onOpenData, onOpenShBook = onOpenShBook)
        } else if (tab == 0) {
            val filtered = remember(bookQuery, state.books) {
                val n = ArabicText.normalize(bookQuery)
                if (n.isEmpty()) state.books else state.books.filter {
                    ArabicText.normalize(it.title).contains(n) || ArabicText.normalize(it.author ?: "").contains(n)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(value = bookQuery, onValueChange = { bookQuery = it }, singleLine = true,
                        placeholder = { Text("ابحث باسم الكتاب أو المؤلف") }, modifier = Modifier.fillMaxWidth())
                }
                items(filtered, key = { it.id }) { b ->
                    Surface(onClick = { onOpenBook(b.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(3.dp))
                            Text(listOfNotNull(b.author, b.deathYear?.takeIf { it.isNotBlank() }?.let { "ت ${ArabicText.arabicDigits(it)} هـ" },
                                "${ArabicText.arabicDigits(b.hadithCount)} حديثًا").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(value = rawiQuery, onValueChange = { rawiQuery = it; rawis = if (it.length >= 2) vm.searchRawis(it) else emptyList() },
                        singleLine = true, placeholder = { Text("اسم الراوي أو شهرته") }, modifier = Modifier.fillMaxWidth())
                }
                if (rawiQuery.length >= 2 && rawis.isEmpty()) item { EmptyState("لا راوي بهذا الاسم", "") }
                items(rawis, key = { it.id }) { n -> NarratorRow(n) { onOpenRawi(n.id) } }
            }
        }
    }
}

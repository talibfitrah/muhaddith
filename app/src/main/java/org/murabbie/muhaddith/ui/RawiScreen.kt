package org.murabbie.muhaddith.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Hadith
import org.murabbie.muhaddith.search.ArabicText

/** ترجمة الراوي ومروياته في الحزمة الحالية */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawiScreen(vm: MuhaddithViewModel, rawiId: Long, onBack: () -> Unit, onOpenHadith: (Long) -> Unit) {
    val rawi = remember(rawiId) { vm.rawi(rawiId) }
    val total = remember(rawiId) { vm.rawiHadithCount(rawiId) }
    var items by remember(rawiId) { mutableStateOf(vm.rawiHadiths(rawiId, 0)) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(rawi?.shohra ?: rawi?.name ?: "الراوي", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } }
        )
    }) { padding ->
        if (rawi == null) { EmptyState("لا ترجمة", "", Modifier.padding(padding)); return@Scaffold }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(rawi.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(6.dp))
                        val rows = listOfNotNull(
                            rawi.shohra?.let { "الشهرة: $it" }, rawi.konya?.let { "الكنية: $it" }, rawi.lakab?.let { "اللقب: $it" },
                            rawi.wasfRotba?.let { "الرتبة: $it" }, rawi.tabaka?.let { "الطبقة: ${ArabicText.arabicDigits(it)}" },
                            rawi.deathYear?.takeIf { it.isNotBlank() }?.let { "الوفاة: ${ArabicText.arabicDigits(it)} هـ" },
                            rawi.baladWafa?.let { "بلد الوفاة: $it" },
                            listOfNotNull(if (rawi.bukhari) "روى له البخاري" else null, if (rawi.muslim) "روى له مسلم" else null)
                                .takeIf { it.isNotEmpty() }?.joinToString(" · "),
                            rawi.marweyaat?.let { "مروياته في الأصل: ${ArabicText.arabicDigits(it)}" }
                        )
                        rows.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
            }
            item { SectionLabel("مروياته في الحزمة الحالية (${ArabicText.arabicDigits(total)})") }
            items(items, key = { it.id }) { h -> HadithRow(h) { onOpenHadith(h.id) } }
            if (items.size < total) {
                item {
                    TextButton(onClick = { items = items + vm.rawiHadiths(rawiId, items.size) }, modifier = Modifier.fillMaxWidth()) {
                        Text("تحميل المزيد")
                    }
                }
            }
        }
    }
}

@Composable
fun HadithRow(h: Hadith, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.bookTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f))
                h.hadithNum?.let { Text("(${ArabicText.arabicDigits(it)})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(6.dp))
                GradePill(h.hokmLabel)
            }
            Spacer(Modifier.height(6.dp))
            Text(display(h.matn).take(170) + if (h.matn.length > 170) "…" else "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** تصفّح كتاب حديثًا حديثًا */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(vm: MuhaddithViewModel, state: UiState, bookId: Long, onBack: () -> Unit, onOpenHadith: (Long) -> Unit, onSearchIn: () -> Unit) {
    val book = state.books.firstOrNull { it.id == bookId }
    var items by remember(bookId) { mutableStateOf(vm.bookHadiths(bookId, 0)) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(book?.title ?: "الكتاب", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { TextButton(onClick = onSearchIn) { Text("ابحث فيه") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                book?.let {
                    Text(listOfNotNull(it.author, it.deathYear?.takeIf { d -> d.isNotBlank() }?.let { d -> "ت ${ArabicText.arabicDigits(d)} هـ" },
                        "${ArabicText.arabicDigits(it.hadithCount)} حديثًا").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(items, key = { it.id }) { h -> HadithRow(h) { onOpenHadith(h.id) } }
            if (book != null && items.size < book.hadithCount) {
                item { TextButton(onClick = { items = items + vm.bookHadiths(bookId, items.size) }, modifier = Modifier.fillMaxWidth()) { Text("تحميل المزيد") } }
            }
        }
    }
}

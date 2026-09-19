package org.murabbie.muhaddith.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Hadith
import org.murabbie.muhaddith.export.ExportOptions
import org.murabbie.muhaddith.search.ArabicText

/**
 * تصدير مجموعة أحاديث (نتائج بحث، أو مجموعة، أو حديث واحد) إلى مستند Word أو PDF،
 * مع اختيار الأحاديث المضمَّنة وما يُكتب في كل حديث.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: MuhaddithViewModel, state: UiState, ids: List<Long>, defaultTitle: String, subtitle: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val hadiths = remember(ids) { ids.mapNotNull { vm.hadith(it) } }
    var selected by remember(ids) { mutableStateOf(ids.toSet()) }
    var o by remember { mutableStateOf(ExportOptions(title = defaultTitle, tashkeel = state.settings.showTashkeel, rulings = state.ahkam.installed, passages = state.ahkam.installed, story = state.shuruh.installed, points = state.shuruh.installed)) }
    var showPick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("تصدير مستند") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value = o.title, onValueChange = { o = o.copy(title = it) }, label = { Text("عنوان المستند") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("الصيغة", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = o.format == "docx", onClick = { o = o.copy(format = "docx") }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Word (docx)") }
                    SegmentedButton(selected = o.format == "pdf", onClick = { o = o.copy(format = "pdf") }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("PDF") }
                }
                Text(if (o.format == "docx") "خط Traditional Arabic، اتجاه يمين، محاذاة يمين، تباعد ٢٤٠، بلا ألوان — قابل للتحرير في Word."
                     else "صفحات A4 بخط النظام، جاهز للطباعة والإرسال.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الأحاديث المضمَّنة: ${ArabicText.arabicDigits(selected.size)} من ${ArabicText.arabicDigits(hadiths.size)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showPick = !showPick }) { Text(if (showPick) "إخفاء" else "اختيار…") }
                        }
                        if (showPick) {
                            Row { TextButton(onClick = { selected = ids.toSet() }) { Text("الكل") }; TextButton(onClick = { selected = emptySet() }) { Text("لا شيء") } }
                            hadiths.forEach { h ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = h.id in selected, onCheckedChange = { c -> selected = if (c) selected + h.id else selected - h.id })
                                    Column(Modifier.weight(1f)) {
                                        Text(h.bookTitle + (h.hadithNum?.let { " (${ArabicText.arabicDigits(it)})" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(display(h.matn).take(110) + if (h.matn.length > 110) "…" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("ما يُكتب في كل حديث", style = MaterialTheme.typography.titleSmall)
                        SwitchRow("سطر التعريف (الاستعلام والتاريخ والمصدر)", o.header) { o = o.copy(header = !o.header) }
                        SwitchRow("ترقيم الأحاديث", o.numbering) { o = o.copy(numbering = !o.numbering) }
                        SwitchRow("الكتاب ورقم الحديث والصفحة", o.bookLine) { o = o.copy(bookLine = !o.bookLine) }
                        SwitchRow("عنوان الباب", o.chapter) { o = o.copy(chapter = !o.chapter) }
                        SwitchRow("الإسناد", o.sanad) { o = o.copy(sanad = !o.sanad) }
                        SwitchRow("المتن", o.matn) { o = o.copy(matn = !o.matn) }
                        SwitchRow("التشكيل", o.tashkeel) { o = o.copy(tashkeel = !o.tashkeel) }
                        SwitchRow("حكم المحدِّث", o.muhaddithGrade) { o = o.copy(muhaddithGrade = !o.muhaddithGrade) }
                        SwitchRow("الصحابي", o.sahabi) { o = o.copy(sahabi = !o.sahabi) }
                        SwitchRow("رجال الإسناد", o.narrators) { o = o.copy(narrators = !o.narrators) }
                        SwitchRow("عدد طرق الحديث", o.waysCount) { o = o.copy(waysCount = !o.waysCount) }
                        if (state.ahkam.installed) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            SwitchRow("أحكام العلماء (مع المصادر)", o.rulings) { o = o.copy(rulings = !o.rulings) }
                            if (o.rulings) {
                                SwitchRow("   أحكام الطرق الأخرى (بحسب دقة الربط: ${org.murabbie.muhaddith.data.RulingPrecision.label(state.settings.rulingPrecision)})", o.rulingsOthers) { o = o.copy(rulingsOthers = !o.rulingsOthers) }
                                SwitchRow("   نص الحكم كما ورد في المصدر", o.rulingQuotes) { o = o.copy(rulingQuotes = !o.rulingQuotes) }
                            }
                            SwitchRow("مواضع الذكر في كتب مختلف الحديث والتخريج والألباني", o.passages) { o = o.copy(passages = !o.passages) }
                        }
                        if (state.shuruh.installed) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            SwitchRow("سبب الحديث وسياقه (روايات ثابتة وكتب أسباب الورود)", o.story) { o = o.copy(story = !o.story) }
                            SwitchRow("شروح العلماء واستنباطاتهم (قال الإمام فلان (ت …): …)", o.points) { o = o.copy(points = !o.points) }
                            if (o.points) {
                                SwitchRow("   تقسيم الاستنباطات بحسب الموضوع (عقدية/فقهية/اجتماعية…)", o.pointsByCategory) { o = o.copy(pointsByCategory = !o.pointsByCategory) }
                                Text("   الموضوعات المضمَّنة (لا شيء محدَّد = الكل):", style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    org.murabbie.muhaddith.data.SharhPoint.CATEGORIES.forEach { c ->
                                        FilterChip(selected = c in o.pointCategories, onClick = { o = o.copy(pointCategories = if (c in o.pointCategories) o.pointCategories - c else o.pointCategories + c) }, label = { Text(c) })
                                    }
                                }
                                Text("   أقصى عدد للاستنباطات لكل حديث: ${ArabicText.arabicDigits(o.maxPoints)}", style = MaterialTheme.typography.labelMedium)
                                Slider(value = o.maxPoints.toFloat(), onValueChange = { o = o.copy(maxPoints = it.toInt()) }, valueRange = 5f..200f, steps = 38)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("حجم خط المتن: ${ArabicText.arabicDigits(o.fontSize)}", style = MaterialTheme.typography.labelLarge)
                        Slider(value = o.fontSize.toFloat(), onValueChange = { o = o.copy(fontSize = it.toInt()) }, valueRange = 12f..22f, steps = 9)
                    }
                }
            }
            item {
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                        busy = true; result = null
                        vm.exportDocument(ids.filter { it in selected }, o, subtitle, share = true) { msg -> result = msg; busy = false }
                    }) { Text(if (busy) "جارٍ الإنشاء…" else "إنشاء ومشاركة") }
                    OutlinedButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                        busy = true; result = null
                        vm.exportDocument(ids.filter { it in selected }, o, subtitle, share = false) { msg -> result = msg; busy = false }
                    }) { Text("حفظ في التنزيلات") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("«إنشاء ومشاركة» يفتح قائمة التطبيقات (Word، الطابعة، واتساب…)؛ و«حفظ» يضع نسخة في Download/Muhaddith.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

package org.murabbie.muhaddith.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Hadith
import org.murabbie.muhaddith.data.Narrator
import org.murabbie.muhaddith.search.ArabicText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HadithScreen(
    vm: MuhaddithViewModel,
    state: UiState,
    hadithId: Long,
    onBack: () -> Unit,
    onOpenHadith: (Long) -> Unit,
    onOpenRawi: (Long) -> Unit,
    onOpenSection: (Long) -> Unit = {},
    onOpenData: () -> Unit = {},
    onExport: (Hadith) -> Unit = {},
    onOpenShSection: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val hadith = remember(hadithId) { vm.hadith(hadithId) }
    val ahkamInstalled = state.ahkam.installed
    val precision = state.settings.rulingPrecision
    var rulings by remember(hadithId, ahkamInstalled, precision) { mutableStateOf<org.murabbie.muhaddith.data.RulingsBundle?>(null) }
    var passages by remember(hadithId, ahkamInstalled, precision) { mutableStateOf<List<org.murabbie.muhaddith.data.PassageRef>?>(null) }
    val shInstalled = state.shuruh.installed
    var points by remember(hadithId, shInstalled, precision) { mutableStateOf<List<org.murabbie.muhaddith.data.SharhPoint>?>(null) }
    var stories by remember(hadithId, shInstalled) { mutableStateOf<List<org.murabbie.muhaddith.data.Story>?>(null) }
    var asbab by remember(hadithId, shInstalled, precision) { mutableStateOf<List<org.murabbie.muhaddith.data.PassageRef>?>(null) }
    var shSections by remember(hadithId, shInstalled, precision) { mutableStateOf<List<org.murabbie.muhaddith.data.PassageRef>?>(null) }
    LaunchedEffect(hadithId, ahkamInstalled, precision) {
        if (hadith != null && ahkamInstalled) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            rulings = vm.rulingsFor(hadith)
            passages = vm.passagesFor(hadith)
        }
    }
    LaunchedEffect(hadithId, shInstalled, precision) {
        if (hadith != null && shInstalled) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            stories = vm.storiesFor(hadith); asbab = vm.asbabFor(hadith)
            points = vm.pointsFor(hadith); shSections = vm.sharhSectionsFor(hadith)
        }
    }
    val narrators = remember(hadithId) { vm.narrators(hadithId) }
    val sahaba = remember(hadithId) { vm.sahaba(hadithId) }
    val cluster = remember(hadithId) { vm.cluster(hadith?.clusterId) }
    val wayCount = remember(hadithId) { hadith?.let { vm.clusterWayCount(it) } ?: 0 }
    val clusterSahaba = remember(hadithId) { vm.clusterSahaba(hadith?.clusterId) }
    var ways by remember(hadithId) { mutableStateOf<List<Hadith>?>(null) }
    var inCollections by remember(hadithId) { mutableStateOf(hadith?.let { vm.collectionsContaining(it.bhid) } ?: emptySet()) }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(hadith?.bookTitle ?: "الحديث", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
                actions = {
                    IconButton(onClick = {
                        hadith?.let {
                            val text = buildString {
                                append(it.bookTitle); it.hadithNum?.let { n -> append(" (${ArabicText.arabicDigits(n)})") }
                                append("\n\n"); it.sanad?.let { s -> append(s).append("\n\n") }
                                append(it.matn); it.hokmLabel?.let { g -> append("\n\nالحكم: ").append(g) }
                                rulings?.own?.take(8)?.forEach { r -> append("\n${r.scholar}: ${r.grade} — ${r.source ?: ""}") }
                                append("\n\n— المحدِّث")
                            }
                            val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(i, "مشاركة الحديث"))
                        }
                    }) { Icon(Icons.Default.Share, contentDescription = "مشاركة") }
                    IconButton(onClick = { showPicker = true }) { Icon(Icons.Default.BookmarkAdd, contentDescription = "إضافة إلى مجموعة") }
                    hadith?.let { h -> IconButton(onClick = { onExport(h) }) { Icon(Icons.Default.Description, contentDescription = "تصدير Word/PDF") } }
                }
            )
        }
    ) { padding ->
        if (hadith == null) { EmptyState("تعذّر العثور على الحديث", "", Modifier.padding(padding)); return@Scaffold }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                hadith.hadithNum?.let { Text("رقم ${ArabicText.arabicDigits(it)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                hadith.pageNum?.let { Text("· ص ${ArabicText.arabicDigits(it)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                GradePill(hadith.hokmLabel)
            }

            hadith.chapter?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            }

            if (!hadith.sanad.isNullOrBlank()) {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        SectionLabel("الإسناد")
                        Text(display(hadith.sanad), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    SectionLabel("المتن")
                    Text(display(hadith.matn), style = MatnStyle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            StorySection(stories, asbab, shInstalled, onOpenHadith = onOpenHadith, onOpenSection = onOpenShSection)

            RulingsSection(rulings, ahkamInstalled, precision, onOpenSection = onOpenSection,
                onOpenHadith = { bhid -> vm.hadithByBhid(bhid)?.let { onOpenHadith(it.id) } }, onOpenData = onOpenData,
                onPrecision = { p -> vm.updateSettings { it.copy(rulingPrecision = p) } })
            PassagesSection(passages, ahkamInstalled, onOpenSection = onOpenSection)

            PointsSection(points, shSections, shInstalled, onOpenSection = onOpenShSection, onOpenData = onOpenData, outdated = state.shuruh.outdated, vm = vm)

            if (sahaba.isNotEmpty()) {
                Column {
                    SectionLabel("الصحابي")
                    sahaba.forEach { NarratorRow(it) { onOpenRawi(it.id) } }
                }
            }

            if (narrators.isNotEmpty()) {
                Column {
                    SectionLabel("رجال الإسناد (${ArabicText.arabicDigits(narrators.size)})")
                    narrators.forEach { NarratorRow(it) { onOpenRawi(it.id) } }
                }
            }

            if (cluster != null) {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        SectionLabel("الحديث المجمَّع")
                        cluster.taraf?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                "${ArabicText.arabicDigits(wayCount)} رواية في هذه الحزمة",
                                cluster.sahabaCount?.let { "${ArabicText.arabicDigits(it)} صحابيًّا" },
                                cluster.mokararat?.let { "${ArabicText.arabicDigits(it)} تكرارًا في الأصل" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (clusterSahaba.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SectionLabel("صحابة الحديث المجمَّع")
                            clusterSahaba.take(12).forEach { cs ->
                                Surface(onClick = { onOpenRawi(cs.rawi.id) }, shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(cs.rawi.shohra ?: cs.rawi.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        cs.wayCount?.let { Text("${ArabicText.arabicDigits(it)} طرق", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                        if (wayCount > 1) {
                            Spacer(Modifier.height(8.dp))
                            if (ways == null) {
                                TextButton(onClick = { ways = vm.clusterWays(hadith) }) { Text("اعرض الطرق الأخرى (${ArabicText.arabicDigits(wayCount - 1)})") }
                            } else {
                                ways!!.forEach { other ->
                                    Spacer(Modifier.height(6.dp))
                                    Surface(onClick = { onOpenHadith(other.id) }, shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(other.bookTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                                GradePill(other.hokmLabel)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(display(other.matn).take(140) + if (other.matn.length > 140) "…" else "", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker && hadith != null) {
        CollectionPicker(
            collections = state.collections, selected = inCollections,
            onToggle = { cid ->
                vm.toggleInCollection(cid, hadith.bhid)
                inCollections = if (cid in inCollections) inCollections - cid else inCollections + cid
            },
            onCreate = { vm.createCollection(it) }, onDismiss = { showPicker = false }
        )
    }
}

@Composable
fun NarratorRow(n: Narrator, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.shohra ?: n.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                n.wasfRotba?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            val meta = listOfNotNull(n.konya, n.deathYear?.takeIf { it.isNotBlank() }?.let { "ت ${ArabicText.arabicDigits(it)} هـ" }).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CollectionPicker(
    collections: List<org.murabbie.muhaddith.data.SavedCollection>, selected: Set<Long>,
    onToggle: (Long) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("مجموعاتي") },
        text = {
            Column {
                if (collections.isEmpty()) Text("لا مجموعات بعد. أنشئ واحدة:", style = MaterialTheme.typography.bodySmall)
                collections.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.id in selected, onCheckedChange = { onToggle(c.id) })
                        Text(c.name, Modifier.weight(1f))
                        Text(ArabicText.arabicDigits(c.itemCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("مجموعة جديدة") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (newName.isNotBlank()) { onCreate(newName.trim()); newName = "" } }) { Text("إضافة") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } }
    )
}

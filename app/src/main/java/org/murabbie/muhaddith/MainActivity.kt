package org.murabbie.muhaddith

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import org.murabbie.muhaddith.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("البحث", Icons.Default.Search),
    LIBRARY("المكتبة", Icons.AutoMirrored.Filled.MenuBook),
    COLLECTIONS("مجموعاتي", Icons.Default.Bookmarks),
    SETTINGS("الإعدادات", Icons.Default.Settings)
}

private sealed class Route {
    data class HadithR(val id: Long) : Route()
    data class RawiR(val id: Long) : Route()
    data class BookR(val id: Long) : Route()
    object DataR : Route()
    data class MkBookR(val id: Long) : Route()
    data class SectionR(val id: Long) : Route()
    object ScholarsR : Route()
    data class ScholarR(val s: org.murabbie.muhaddith.data.Scholar) : Route()
    data class ExportR(val ids: List<Long>, val title: String, val subtitle: String?) : Route()
    data class ShSectionR(val id: Long) : Route()
    data class ShBookR(val id: Long) : Route()
}

class MainActivity : ComponentActivity() {
    private val sharedUris = MutableStateFlow<List<android.net.Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            val vm: MuhaddithViewModel = viewModel()
            vmRef = vm
            val state by vm.state.collectAsState()
            val s = state.settings
            // إبقاء الشاشة مضاءة أثناء التحميل إن طُلب
            LaunchedEffect(state.pack.busy, s.keepScreenOnWhileImporting) {
                if (state.pack.busy && s.keepScreenOnWhileImporting) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MuhaddithTheme(theme = s.theme, fontScale = s.fontScale, serif = s.serifFont) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDisplay provides s) { MuhaddithApp(vm, state, sharedUris) }
            }
        }
    }

    private var vmRef: MuhaddithViewModel? = null
    override fun onStop() { super.onStop(); vmRef?.onBackground() }
    override fun onResume() { super.onResume(); vmRef?.onForeground() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** ملفات وصلت عبر «مشاركة» من مدير الملفات */
    private fun handleShare(intent: android.content.Intent?) {
        intent ?: return
        val uris: List<android.net.Uri> = when (intent.action) {
            android.content.Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtraCompat(android.content.Intent.EXTRA_STREAM))
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtraCompat(android.content.Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { grantUriPermission(packageName, it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            sharedUris.value = uris
            intent.action = null
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.getParcelableExtraCompat(key: String): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, android.net.Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun android.content.Intent.getParcelableArrayListExtraCompat(key: String): List<android.net.Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, android.net.Uri::class.java)
        else getParcelableArrayListExtra(key)) ?: emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuhaddithApp(vm: MuhaddithViewModel, state: UiState, sharedUris: MutableStateFlow<List<android.net.Uri>>) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf(Tab.SEARCH) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.clearToast() } }
    val stack = remember { mutableStateListOf<Route>() }
    val shared by sharedUris.collectAsState()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) { tab = Tab.SETTINGS; stack.clear(); stack.add(Route.DataR); vm.onSharedFiles(shared); sharedUris.value = emptyList() }
    }

    if (!state.ready) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("المحدِّث", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                CircularProgressIndicator()
                androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                Text("يفتح قاعدة البيانات…", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    fun push(r: Route) { if (stack.size < 40) stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    when (val top = stack.lastOrNull()) {
        is Route.HadithR -> {
            HadithScreen(vm, state, top.id, onBack = ::pop,
                onOpenHadith = { push(Route.HadithR(it)) }, onOpenRawi = { push(Route.RawiR(it)) },
                onOpenSection = { push(Route.SectionR(it)) }, onOpenData = { push(Route.DataR) },
                onExport = { h -> push(Route.ExportR(listOf(h.id), h.bookTitle + (h.hadithNum?.let { " (${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)})" } ?: ""), null)) },
                onOpenShSection = { push(Route.ShSectionR(it)) })
            return
        }
        is Route.MkBookR -> { MkBookScreen(vm, top.id, onBack = ::pop, onOpenSection = { push(Route.SectionR(it)) }); return }
        is Route.SectionR -> { SectionScreen(vm, top.id, onBack = ::pop, onOpenSection = { stack.removeAt(stack.lastIndex); push(Route.SectionR(it)) }, onOpenHadith = { push(Route.HadithR(it)) }); return }
        is Route.ScholarsR -> { ScholarsScreen(vm, onBack = ::pop, onOpenScholar = { push(Route.ScholarR(it)) }); return }
        is Route.ShSectionR -> { ShSectionScreen(vm, top.id, onBack = ::pop, onOpenSection = { stack.removeAt(stack.lastIndex); push(Route.ShSectionR(it)) }, onOpenHadith = { push(Route.HadithR(it)) }); return }
        is Route.ShBookR -> { ShBookScreen(vm, top.id, onBack = ::pop, onOpenSection = { push(Route.ShSectionR(it)) }); return }
        is Route.ExportR -> { ExportScreen(vm, state, top.ids, top.title, top.subtitle, onBack = ::pop); return }
        is Route.ScholarR -> { ScholarScreen(vm, top.s, onBack = ::pop, onOpenSection = { push(Route.SectionR(it)) }, onOpenHadith = { push(Route.HadithR(it)) }); return }
        is Route.RawiR -> {
            RawiScreen(vm, top.id, onBack = ::pop, onOpenHadith = { push(Route.HadithR(it)) })
            return
        }
        is Route.BookR -> {
            BookScreen(vm, state, top.id, onBack = ::pop, onOpenHadith = { push(Route.HadithR(it)) },
                onSearchIn = { vm.setBookFilter(top.id); stack.clear(); tab = Tab.SEARCH })
            return
        }
        is Route.DataR -> {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("حزم البيانات") },
                        navigationIcon = { IconButton(onClick = ::pop) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
                },
                snackbarHost = { SnackbarHost(snackbar) }
            ) { padding -> Surface(Modifier.fillMaxSize().padding(padding)) { DataScreen(vm, state) } }
            return
        }
        null -> {}
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("المحدِّث") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) })
                }
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.SEARCH -> SearchScreen(
                    state = state, onQueryChange = vm::onQueryChange, onSearch = vm::search,
                    onToggleEngine = vm::toggleEngine, onScope = vm::setScope, onBookFilter = vm::setBookFilter,
                    onHokmFilter = vm::setHokmFilter, onOpenHadith = { push(Route.HadithR(it)) },
                    onSaveSearch = vm::saveCurrentSearch, onOpenData = { push(Route.DataR) }, onBookScope = vm::setBookScope, onUpdate = { vm.updateAction(appCtx) },
                    onExport = { push(Route.ExportR(state.results.map { it.hadith.id }, "بحث: " + state.query, "الاستعلام: «${state.query}» — " + state.engines.joinToString("، ") { it.label })) }
                )
                Tab.LIBRARY -> LibraryScreen(vm, state, onOpenBook = { push(Route.BookR(it)) }, onOpenRawi = { push(Route.RawiR(it)) },
                    onOpenMkBook = { push(Route.MkBookR(it)) }, onOpenSection = { push(Route.SectionR(it)) }, onOpenScholars = { push(Route.ScholarsR) }, onOpenData = { push(Route.DataR) },
                    onOpenShBook = { push(Route.ShBookR(it)) })
                Tab.COLLECTIONS -> CollectionsScreen(vm, state, onOpenHadith = { push(Route.HadithR(it)) },
                    onRunSaved = { vm.runSavedSearch(it); tab = Tab.SEARCH },
                    onExport = { name, ids -> push(Route.ExportR(ids, "مجموعة: $name", "من مجموعاتي")) })
                Tab.SETTINGS -> SettingsScreen(vm, state, onOpenData = { push(Route.DataR) })
            }
        }
    }
}

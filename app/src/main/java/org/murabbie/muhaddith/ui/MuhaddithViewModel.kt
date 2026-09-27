package org.murabbie.muhaddith.ui

import android.app.Application
import android.net.Uri
import org.murabbie.muhaddith.search.ArabicText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.murabbie.muhaddith.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** حزمة على الخادم تختلف عن المثبَّت: ناقصة، أو أحدث بناءً/بصمةً. auto=false: لا تُنزَّل تلقائيًّا (الخادم نفسه لم يُحدَّث بعد) */
data class PackUpdate(val pack: RemotePack, val reason: String, val installed: Boolean, val auto: Boolean = true)

data class PackState(
    val busy: Boolean = false,
    val done: Long = 0,
    val total: Long = -1,
    val phase: Int = 0,          // ٠ تنزيل، ١ تحقّق، ٢ فكّ ضغط
    val message: String? = null,
    val error: String? = null,
    val remote: List<RemotePack> = emptyList(),
    val remoteError: String? = null,
    val loadingRemote: Boolean = false,
    val partialBytes: Long = 0,
    /** ما وُجد في مجلد الهاتف الذي اختاره المستخدم */
    val found: List<FoundPack> = emptyList(),
    val folderName: String? = null,
    val scanning: Boolean = false,
    val allFilesAccess: Boolean = false,
    // مشاركة مع جهاز آخر
    val sharing: Boolean = false,
    val shareUrl: String? = null,
    val shareHashing: Boolean = false,
    val servedBytes: Long = 0,
    val sdAvailable: Boolean = false,
    val online: Boolean = true,
    val onWifi: Boolean = false
)

data class UiState(
    val ready: Boolean = false,
    val query: String = "",
    val engines: Set<Engine> = setOf(Engine.LITERAL, Engine.MORPH),
    val scope: SearchScope = SearchScope.MATN,
    val bookFilter: Long? = null,
    val hokmFilter: Int? = null,
    val results: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val expansion: List<QueryTerm> = emptyList(),
    val highlight: List<String> = emptyList(),
    val books: List<Book> = emptyList(),
    val collections: List<SavedCollection> = emptyList(),
    val savedSearches: List<Repository.SavedSearch> = emptyList(),
    val history: List<String> = emptyList(),
    val dataset: DatasetInfo? = null,
    val pack: PackState = PackState(),
    val packsBaseUrl: String = "",
    val settings: AppSettings = AppSettings(),
    val toast: String? = null,
    val semanticReady: Boolean = false,
    val semanticModel: Boolean = false,
    val semanticVectors: Boolean = false,
    val semanticBytes: Long = 0,
    /** حزمة أحكام العلماء ومختلف الحديث */
    val ahkam: AhkamInfo = AhkamInfo(installed = false),
    val shuruh: ShuruhInfo = ShuruhInfo(installed = false),
    // تحديث التطبيق من الخادم
    val appVersionName: String = "",
    val updateAvailable: RemoteApp? = null,
    /** مسار APK المنزَّل الجاهز للتثبيت */
    val updateFile: String? = null,
    val updateChecking: Boolean = false,
    val updateMessage: String? = null,
    /** حزم على الخادم أحدث من المثبَّت (أو ناقصة تكمّل المثبَّت) */
    val dataUpdates: List<PackUpdate> = emptyList(),
    val packLog: List<String> = emptyList(),
    /** حزم في طابور الانتظار تُنزَّل بعد انتهاء العمل الجاري */
    val queued: List<String> = emptyList()
)

class MuhaddithViewModel(app: Application) : AndroidViewModel(app) {

    private lateinit var repo: Repository
    private lateinit var packs: DataPackManager
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // فتح القاعدة (ونسخها من الحزمة أول مرة) خارج الخيط الرئيسي
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) {
                repo = Repository(app)
                packs = DataPackManager(app, repo.database)
                repo.loadSettings()
            }
            PackWorker.wifiOnly = settings.wifiOnly
            PackWorker.onDone = { viewModelScope.launch {
                withContext(Dispatchers.IO) { repo.semantic.release() }
                val err = PackWorker.state.value.error
                if (err != null) {
                    withContext(Dispatchers.IO) { packs.log("خطأ: $err") }
                    if (activeDownloadIds.isNotEmpty()) failedThisSession.addAll(activeDownloadIds)   // فشل التثبيت: لا يُعاد تلقائيًّا هذه الجلسة
                }
                activeDownloadIds = emptyList()
                refreshAll().join(); packLogRefresh(); computeDataUpdates()
                runNextPending()
                if (pending.isEmpty() && !PackWorker.isBusy()) autoMaintain()   // يكمل السلسلة: المتون ← الأحكام ← الشروح ← الدلالي
            } }
            viewModelScope.launch {
                PackWorker.state.collect { w ->
                    _state.value = _state.value.copy(pack = _state.value.pack.copy(
                        busy = w.busy, done = w.done, total = w.total, phase = w.phase,
                        message = w.message, error = w.error
                    ))
                }
            }
            _state.value = _state.value.copy(
                ready = true, settings = settings,
                engines = settings.defaultEngines,
                scope = runCatching { SearchScope.valueOf(settings.defaultScope) }.getOrDefault(SearchScope.MATN)
            )
            refreshAll()
            autoScan()
            _state.value = _state.value.copy(appVersionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault(""))
            checkForUpdate(silent = true)
        }
    }

    // ---------- تحديث التطبيق ----------

    private fun installedVersionCode(): Long {
        val app = getApplication<Application>()
        return runCatching {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)
    }

    /** يقرأ كتلة app من بيان الخادم؛ silent = بلا رسائل عند الفشل أو عدم وجود جديد */
    fun checkForUpdate(silent: Boolean = false, applyAll: Boolean = false) {
        if (!PackWorker.isOnline(getApplication())) { if (!silent) _state.value = _state.value.copy(updateMessage = "لا اتصال بالإنترنت"); return }
        _state.value = _state.value.copy(updateChecking = true, updateMessage = null)
        lastCheck = System.currentTimeMillis()
        viewModelScope.launch {
            val (remote, err) = withContext(Dispatchers.IO) {
                try { packs.fetchManifest(); packs.remoteApp to null } catch (e: Exception) { null to (e.message ?: e.toString()) }
            }
            val cur = installedVersionCode()
            // في نكهة المتجر لا يُعرض تحديث APK (سياسة Google Play)؛ التحديث من المتجر نفسه
            val newer = remote?.takeIf { org.murabbie.muhaddith.BuildConfig.SELF_UPDATE && it.versionCode > cur && it.apks.isNotEmpty() }
            val ready = _state.value.updateFile?.takeIf { java.io.File(it).exists() && newer != null }
            _state.value = _state.value.copy(
                updateChecking = false, updateAvailable = newer, updateFile = ready,
                pack = _state.value.pack.copy(remote = if (err == null) packs.lastManifest else _state.value.pack.remote),
                updateMessage = when {
                    silent -> null
                    err != null -> "تعذّر الاتصال بالخادم ($err)"
                    newer == null -> "أنت على أحدث إصدار (${ArabicText.arabicDigits(_state.value.appVersionName)})"
                    else -> null
                }
            )
            if (err == null) {
                computeDataUpdates()
                if (silent || applyAll) autoMaintain(force = applyAll)
                // التطبيق: يُنزَّل التحديث تلقائيًّا على الواي فاي (التثبيت نفسه يحتاج ضغطة لأن النظام يطلبها)
                if (newer != null && ready == null && !PackWorker.isBusy() && pending.isEmpty() && (applyAll || PackWorker.onWifi(getApplication())) && _state.value.settings.autoDataUpdate) {
                    packs.log("تنزيل تلقائي لتحديث التطبيق ${newer.versionName}")
                    downloadUpdate(); lastCheck = System.currentTimeMillis()
                } else if (applyAll && newer == null && _state.value.dataUpdates.isEmpty()) {
                    _state.value = _state.value.copy(toast = "كل شيء محدَّث: التطبيق ${ArabicText.arabicDigits(_state.value.appVersionName)} والبيانات مطابقة للخادم ✓")
                }
            }
            packLogRefresh()
        }
    }

    /** ينزّل APK التحديث ثم يعرض زر التثبيت */
    fun downloadUpdate() {
        val app = _state.value.updateAvailable ?: return
        runPack("جارٍ تنزيل التحديث ${ArabicText.arabicDigits(app.versionName)}…") { cb ->
            val f = packs.downloadUpdate(app, cb)
            _state.value = _state.value.copy(updateFile = f.absolutePath, toast = "تحديث التطبيق ${ArabicText.arabicDigits(app.versionName)} جاهز — اضغط «ثبّت الآن»")
        }
    }

    /** يفتح مثبّت النظام على الملف المنزَّل (يطلب النظام إذن «تثبيت تطبيقات غير معروفة» أول مرة) */
    fun installUpdate(ctx: android.content.Context) {
        // نكهة المتجر: لا تثبيت APK من داخل التطبيق (سياسة Google Play)؛ الشرط ثابت وقت الترجمة فيُحذف الجسم من نسخة play
        if (!org.murabbie.muhaddith.BuildConfig.SELF_UPDATE) return
        val path = _state.value.updateFile ?: return
        val f = java.io.File(path); if (!f.exists()) { _state.value = _state.value.copy(updateFile = null); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ctx.startActivity(i) } catch (e: Exception) { _state.value = _state.value.copy(updateMessage = "تعذّر فتح المثبّت: ${e.message}") }
    }

    /** ضغطة واحدة: تنزيل إن لم يُنزَّل، وإلا تثبيت */
    fun updateAction(ctx: android.content.Context) { if (_state.value.updateFile != null) installUpdate(ctx) else downloadUpdate() }

    private fun refreshAll(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val books = withContext(Dispatchers.IO) { repo.books() }
            val semReady = withContext(Dispatchers.IO) { repo.semanticReady() }
            repo.setSemanticWeight(_state.value.settings.semanticWeight.toDouble())
            _state.value = _state.value.copy(
                books = books,
                semanticReady = semReady,
                semanticModel = withContext(Dispatchers.IO) { repo.semanticModelReady() },
                semanticVectors = withContext(Dispatchers.IO) { repo.semanticVectorsReady() },
                semanticBytes = withContext(Dispatchers.IO) { packs.semanticBytes() },
                // إن صار البحث الدلالي جاهزًا الآن (بعد تنزيل النموذج والمتّجهات) فعّله وفق إعداد المستخدم الافتراضي
                engines = if (!semReady) _state.value.engines - Engine.SEMANTIC
                          else if (!_state.value.semanticReady && _state.value.settings.defaultSemantic) _state.value.engines + Engine.SEMANTIC else _state.value.engines,
                dataset = withContext(Dispatchers.IO) { repo.datasetInfo() },
                ahkam = withContext(Dispatchers.IO) { repo.ahkam.info() },
                shuruh = withContext(Dispatchers.IO) { repo.shuruh.info() },
                collections = withContext(Dispatchers.IO) { repo.collections() },
                savedSearches = withContext(Dispatchers.IO) { repo.savedSearches() },
                history = withContext(Dispatchers.IO) { repo.history() },
                packsBaseUrl = packs.baseUrl,
                pack = _state.value.pack.copy(
                    partialBytes = packs.partialBytes(), allFilesAccess = packs.hasAllFilesAccess(),
                    sdAvailable = MuhaddithDatabase.sdDir(getApplication()) != null,
                    online = PackWorker.isOnline(getApplication()), onWifi = PackWorker.onWifi(getApplication()),
                    sharing = ShareServer.running, shareUrl = if (ShareServer.running) ShareServer.url() else null,
                    shareHashing = ShareServer.hashing, servedBytes = ShareServer.served
                )
            )
        }
    }

    fun workState(): kotlinx.coroutines.flow.StateFlow<WorkState> = PackWorker.state
    val speed: Long get() = PackWorker.state.value.speedBps
    val eta: Long get() = PackWorker.state.value.etaSec

    /** حزم فشل تثبيتها هذه الجلسة (مخطط أحدث/تالفة) — لا تُعاد تلقائيًّا كي لا تتكرّر بلا نهاية */
    private val failedThisSession = HashSet<String>()
    private var activeDownloadIds: List<String> = emptyList()
    /** أوقف المستخدم التنزيل يدويًّا: لا صيانة تلقائية حتى يطلبها صراحةً أو يُعاد تشغيل التطبيق */
    private var autoSuppressed = false

    fun cancelWork() {
        autoSuppressed = true
        if (activeDownloadIds.isNotEmpty()) failedThisSession.addAll(activeDownloadIds)
        val dropped = pending.size
        pending.clear()
        _state.value = _state.value.copy(queued = emptyList(), toast = if (dropped > 0) "أُوقف التنزيل وأُلغيت ${ArabicText.arabicDigits(dropped)} في الانتظار — لن يبدأ تلقائيًّا حتى تطلبه" else "أُوقف التنزيل — لن يبدأ تلقائيًّا حتى تطلبه")
        PackWorker.cancel()
    }

    // ---------- المشاركة مع جهاز آخر ----------
    fun startSharing() {
        val d = repo.datasetInfo()
        if (d.isEmpty) { _state.value = _state.value.copy(toast = "لا حزمة مثبَّتة لمشاركتها."); return }
        val f = if (d.isImported) MuhaddithDatabase.fullFile(getApplication()) else java.io.File(getApplication<Application>().filesDir, MuhaddithDatabase.BUNDLED_FILE)
        try { ShareServer.start(f, d) } catch (e: Exception) { _state.value = _state.value.copy(toast = "تعذّر تشغيل المشاركة: ${e.message}"); return }
        _state.value = _state.value.copy(pack = _state.value.pack.copy(sharing = true, shareUrl = ShareServer.url(), shareHashing = true))
        viewModelScope.launch {
            while (ShareServer.running) {
                kotlinx.coroutines.delay(1500)
                _state.value = _state.value.copy(pack = _state.value.pack.copy(shareHashing = ShareServer.hashing, servedBytes = ShareServer.served, shareUrl = ShareServer.url()))
            }
        }
    }

    fun stopSharing() { ShareServer.stop(); _state.value = _state.value.copy(pack = _state.value.pack.copy(sharing = false, shareUrl = null)) }

    /** تنزيل من جهاز آخر: عنوانه مثل http://192.168.1.5:8765/ */
    fun downloadFromDevice(url: String) {
        val u = url.trim().let { if (it.startsWith("http")) it else "http://$it" }.let { if (it.endsWith("/")) it else "$it/" }
        setPacksBaseUrl(u)
    }

    // ---------- مكان التخزين ----------
    fun moveStorage(to: String) {
        if (PackWorker.isBusy()) return
        PackWorker.start(getApplication(), if (to == "sd") "نقل الحزمة إلى بطاقة الذاكرة…" else "نقل الحزمة إلى الذاكرة الداخلية…") { cb -> packs.moveStorage(to, cb) }
    }

    // ---------- نطاق الكتب ----------
    fun setBookScope(ids: Set<Long>) { updateSettings { it.copy(bookScope = ids) }; if (_state.value.searched) search() }

    // ---------- الإعدادات ----------

    /** استعادة الافتراضيات مع الإبقاء على ما يخصّ الملفات (مجلد الأجزاء المختار) */
    fun resetSettings() = updateSettings { AppSettings(dataFolderUri = it.dataFolderUri) }

    fun updateSettings(fn: (AppSettings) -> AppSettings) {
        val next = fn(_state.value.settings)
        _state.value = _state.value.copy(settings = next)
        PackWorker.wifiOnly = next.wifiOnly
        repo.setSemanticWeight(next.semanticWeight.toDouble())
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.saveSettings(next) } }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.clearHistory() }
            _state.value = _state.value.copy(history = emptyList(), toast = "مُسح سجل البحث.")
        }
    }

    fun exportUserData(): String = repo.exportUserData()

    fun importUserData(json: String) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { repo.importUserData(json) }.getOrElse { -1 } }
            _state.value = _state.value.copy(toast = if (n >= 0) "استُوردت ${org.murabbie.muhaddith.search.ArabicText.arabicDigits(n)} عناصر." else "الملف ليس نسخة احتياطية صالحة.")
            refreshCollections()
            _state.value = _state.value.copy(savedSearches = withContext(Dispatchers.IO) { repo.savedSearches() })
        }
    }

    fun clearToast() { _state.value = _state.value.copy(toast = null) }

    /** فحص تلقائي عند الفتح: المجلد المحفوظ أو المجلدات العامة إن أُذن بها */
    fun autoScan() {
        if (!_state.value.settings.autoScanOnOpen) return
        val saved = _state.value.settings.dataFolderUri
        if (packs.hasAllFilesAccess()) scanPublic()
        else if (saved != null) scanFolder(Uri.parse(saved), saved.substringAfterLast(':').ifBlank { null }, persist = false)
    }

    fun scanPublic() {
        _state.value = _state.value.copy(pack = _state.value.pack.copy(scanning = true, folderName = "مجلدات الهاتف"))
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { try { packs.scanPublicFolders() } catch (_: Exception) { emptyList() } }
            _state.value = _state.value.copy(pack = _state.value.pack.copy(found = found, scanning = false, allFilesAccess = packs.hasAllFilesAccess()))
        }
    }

    fun refreshAccess() { _state.value = _state.value.copy(pack = _state.value.pack.copy(allFilesAccess = packs.hasAllFilesAccess())) }

    fun onQueryChange(q: String) { _state.value = _state.value.copy(query = q) }

    fun toggleEngine(e: Engine) {
        if (e == Engine.SEMANTIC && !_state.value.semanticReady) return
        val cur = _state.value.engines.toMutableSet()
        if (e in cur && cur.size > 1) cur.remove(e) else cur.add(e)
        _state.value = _state.value.copy(engines = cur)
        if (_state.value.searched) search()
    }

    fun setScope(s: SearchScope) { _state.value = _state.value.copy(scope = s); if (_state.value.searched) search() }
    fun setBookFilter(id: Long?) { _state.value = _state.value.copy(bookFilter = id); if (_state.value.searched) search() }
    fun setHokmFilter(h: Int?) { _state.value = _state.value.copy(hokmFilter = h); if (_state.value.searched) search() }

    fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        _state.value = s.copy(searching = true)
        viewModelScope.launch {
            val (hits, plan) = withContext(Dispatchers.IO) {
                val p = repo.planSummary(s.query, s.engines)
                val r = repo.search(s.query, s.engines, s.scope, s.bookFilter, s.hokmFilter, s.settings.resultLimit, s.settings.snippetLength, s.settings.bookScope)
                (if (s.settings.rulingsInResults && repo.ahkam.installed) r.map { h -> h.copy(rulingSummary = repo.ahkam.summaryFor(h.hadith.bhid, h.hadith.clusterId, s.settings.rulingPrecision)) } else r) to p
            }
            if (s.settings.keepHistory) withContext(Dispatchers.IO) { repo.recordHistory(s.query) }
            _state.value = _state.value.copy(
                results = hits, searching = false, searched = true,
                expansion = plan.terms, highlight = repo.highlightTerms(plan),
                history = withContext(Dispatchers.IO) { repo.history() }
            )
        }
    }

    fun clearSearch() { _state.value = _state.value.copy(results = emptyList(), searched = false, query = "") }

    // ---------- الحديث ----------
    fun hadith(id: Long): Hadith? = repo.hadith(id)
    fun narrators(id: Long): List<Narrator> = repo.narratorsOf(id)
    fun sahaba(id: Long): List<Narrator> = repo.sahabaOf(id)
    fun cluster(id: Long?): Cluster? = repo.cluster(id)
    fun clusterWays(h: Hadith): List<Hadith> = repo.clusterWays(h.clusterId, h.id)
    fun clusterWayCount(h: Hadith): Int = repo.clusterWayCount(h.clusterId)
    fun clusterSahaba(id: Long?): List<ClusterSahabi> = repo.clusterSahaba(id)
    fun rawi(id: Long): Narrator? = repo.rawi(id)
    fun rawiHadiths(id: Long, offset: Int): List<Hadith> = repo.hadithsOfRawi(id, offset)
    fun rawiHadithCount(id: Long): Int = repo.rawiHadithCount(id)
    fun bookHadiths(id: Long, offset: Int): List<Hadith> = repo.hadithsOfBook(id, offset)
    fun searchRawis(q: String): List<Narrator> = repo.searchRawis(q)

    // ---------- أحكام العلماء ومختلف الحديث ----------
    val ahkam: AhkamRepo get() = repo.ahkam
    fun rulingsFor(h: Hadith): RulingsBundle = repo.ahkam.rulingsFor(h.bhid, h.clusterId, _state.value.settings.rulingPrecision)
    fun passagesFor(h: Hadith): List<PassageRef> = repo.ahkam.passagesFor(h.clusterId, precision = _state.value.settings.rulingPrecision)
    fun hadithByBhid(bhid: String): Hadith? = repo.hadithByBhid(bhid)
    val shuruh: ShuruhRepo get() = repo.shuruh
    fun pointsFor(h: Hadith): List<SharhPoint> = repo.shuruh.pointsFor(h.clusterId, _state.value.settings.rulingPrecision, bhid = h.bhid)
    fun storiesFor(h: Hadith): List<Story> = repo.shuruh.storiesFor(h.clusterId, exclude = h.bhid)
    fun asbabFor(h: Hadith): List<PassageRef> = repo.shuruh.sectionsFor(h.clusterId, listOf("asbab"), _state.value.settings.rulingPrecision)
    /** نقاط شرح وحدة (يُستدعى من الواجهة عند فتح شرح شارح معيّن) */
    suspend fun unitPoints(sectionId: Long): List<String> = withContext(Dispatchers.IO) { repo.shuruh.unitPoints(sectionId) }
    fun sharhSectionsFor(h: Hadith): List<PassageRef> = repo.shuruh.sectionsFor(h.clusterId, listOf("sharh", "modern"), _state.value.settings.rulingPrecision, withSnippet = false, bhid = h.bhid)
    fun firstOfCluster(id: Long): Hadith? = repo.firstOfCluster(id)

    fun removeShuruh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { packs.removeShuruh() }
            _state.value = _state.value.copy(toast = "حُذفت حزمة الشروح")
            refreshAll()
        }
    }

    // ---------- التصدير إلى Word / PDF ----------
    /** يجمع مادة الأحاديث ويبني المستند في الخلفية، ثم يشارك أو يحفظ؛ [done] تستقبل رسالة النتيجة */
    fun exportDocument(ids: List<Long>, o: org.murabbie.muhaddith.export.ExportOptions, subtitle: String?, share: Boolean, done: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val msg = try {
                val bytes = withContext(Dispatchers.IO) {
                    val prec = _state.value.settings.rulingPrecision
                    val items = ids.mapNotNull { id ->
                        val h = repo.hadith(id) ?: return@mapNotNull null
                        org.murabbie.muhaddith.export.ExportItem(
                            hadith = h,
                            sahaba = if (o.sahabi) repo.sahabaOf(h.id) else emptyList(),
                            narrators = if (o.narrators) repo.narratorsOf(h.id) else emptyList(),
                            rulings = if (o.rulings && repo.ahkam.installed) repo.ahkam.rulingsFor(h.bhid, h.clusterId, prec) else null,
                            passages = if (o.passages && repo.ahkam.installed) repo.ahkam.passagesFor(h.clusterId, withSnippet = false, precision = prec) else emptyList(),
                            waysCount = if (o.waysCount) repo.clusterWayCount(h.clusterId) else 0,
                            stories = if (o.story && repo.shuruh.installed) repo.shuruh.storiesFor(h.clusterId, exclude = h.bhid) else emptyList(),
                            asbab = if (o.story && repo.shuruh.installed) repo.shuruh.sectionsFor(h.clusterId, listOf("asbab"), prec) else emptyList(),
                            points = if (o.points && repo.shuruh.installed) repo.shuruh.pointsFor(h.clusterId, prec, bhid = h.bhid) else emptyList()
                        )
                    }
                    val paras = org.murabbie.muhaddith.export.DocExport.build(items, o, subtitle)
                    if (o.format == "pdf") org.murabbie.muhaddith.export.DocExport.pdf(app, paras, o) else org.murabbie.muhaddith.export.DocExport.docx(paras, o)
                }
                val name = org.murabbie.muhaddith.export.DocExport.fileName(o.title, o.format)
                if (share) {
                    val f = withContext(Dispatchers.IO) { org.murabbie.muhaddith.export.ExportFiles.writeCache(app, name, bytes) }
                    val i = android.content.Intent.createChooser(org.murabbie.muhaddith.export.ExportFiles.shareIntent(app, f, o.format), "مشاركة المستند").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    app.startActivity(i)
                    "أُنشئ المستند ($name — ${bytes.size / 1024} ك.ب)"
                } else {
                    val path = withContext(Dispatchers.IO) { org.murabbie.muhaddith.export.ExportFiles.saveToDownloads(app, name, bytes, o.format) }
                    "حُفظ في $path (${bytes.size / 1024} ك.ب)"
                }
            } catch (e: Exception) { "تعذّر التصدير: ${e.message}" }
            done(msg)
        }
    }

    fun removeAhkam() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { packs.removeAhkam() }
            _state.value = _state.value.copy(toast = "حُذفت حزمة الأحكام")
            refreshAll()
        }
    }

    // ---------- المجموعات ----------
    fun collectionsContaining(bhid: String): Set<Long> = repo.collectionsContaining(bhid)
    fun collectionItems(id: Long): List<Hadith> = repo.collectionItems(id)

    fun createCollection(name: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.createCollection(name) }; refreshCollections() }
    }

    fun deleteCollection(id: Long) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteCollection(id) }; refreshCollections() }
    }

    fun toggleInCollection(collectionId: Long, bhid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (collectionId in repo.collectionsContaining(bhid)) repo.removeFromCollection(collectionId, bhid)
                else repo.addToCollection(collectionId, bhid)
            }
            refreshCollections()
        }
    }

    fun saveCurrentSearch(name: String) {
        val s = _state.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveSearch(name, s.query, s.engines, s.scope) }
            _state.value = _state.value.copy(savedSearches = withContext(Dispatchers.IO) { repo.savedSearches() })
        }
    }

    fun runSavedSearch(ss: Repository.SavedSearch) {
        _state.value = _state.value.copy(
            query = ss.query, engines = ss.engines.ifEmpty { setOf(Engine.LITERAL) }, scope = ss.scope
        )
        search()
    }

    fun deleteSavedSearch(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteSavedSearch(id) }
            _state.value = _state.value.copy(savedSearches = withContext(Dispatchers.IO) { repo.savedSearches() })
        }
    }

    private suspend fun refreshCollections() {
        _state.value = _state.value.copy(collections = withContext(Dispatchers.IO) { repo.collections() })
    }

    // ---------- حزمة البيانات ----------

    fun setPacksBaseUrl(u: String) {
        packs.setBaseUrl(u)
        _state.value = _state.value.copy(packsBaseUrl = packs.baseUrl)
        refreshManifest()
    }

    fun refreshManifest() {
        _state.value = _state.value.copy(pack = _state.value.pack.copy(loadingRemote = true, remoteError = null))
        viewModelScope.launch {
            val (list, err) = withContext(Dispatchers.IO) {
                try { packs.fetchManifest() to null } catch (e: Exception) { emptyList<RemotePack>() to (e.message ?: e.toString()) }
            }
            _state.value = _state.value.copy(pack = _state.value.pack.copy(remote = list, remoteError = err, loadingRemote = false))
            if (err == null) computeDataUpdates()
        }
    }

    /** تنزيل حزمة/حزم من قائمة التحديثات */
    fun applyDataUpdates(list: List<PackUpdate>) = downloadPacks(list.map { it.pack })

    fun downloadPack(p: RemotePack) {
        autoSuppressed = false; failedThisSession.remove(p.id); activeDownloadIds = listOf(p.id)   // طلب صريح من المستخدم يُلغي الكبح
        runPack("جارٍ تنزيل «${p.name}»…") { cb -> packs.download(p, cb) }
    }

    /** تنزيل عدة حزم متتابعةً بضغطة واحدة (المتون ثم الأحكام ثم الشروح…) مع تخطّي ما هو مثبَّت */
    fun downloadPacks(list: List<RemotePack>, auto: Boolean = false) {
        if (list.isEmpty()) return
        if (!auto) { autoSuppressed = false; failedThisSession.removeAll(list.map { it.id }.toSet()) }   // طلب صريح يُلغي الكبح لهذه الحزم
        activeDownloadIds = list.map { it.id }
        runPack("جارٍ تنزيل ${ArabicText.arabicDigits(list.size)} حزم…") { cb ->
            for ((i, p) in list.withIndex()) {
                PackWorker.setTitle("(${ArabicText.arabicDigits(i + 1)}/${ArabicText.arabicDigits(list.size)}) جارٍ تنزيل «${p.name}»…")
                packs.download(p, cb)
            }
        }
    }

    /** حزمتا البحث الدلالي اللازمتان لهذا الجهاز: النموذج (إن لم يُثبَّت) ومتّجهات الحزمة المثبَّتة (تسعة/كاملة) */
    fun semanticPacks(): List<RemotePack> {
        val st = _state.value; val remote = st.pack.remote
        val ds = st.dataset?.let { if (it.isFull) "full" else "tisa" } ?: return emptyList()
        val want = listOfNotNull(if (st.semanticModel) null else "model_bge", if (st.semanticVectors) null else "vectors_$ds")
        return want.mapNotNull { id -> remote.firstOrNull { it.id == id } }
    }

    /** لماذا لا يعمل البحث الدلالي؟ (null = يعمل) */
    fun semanticProblem(): String? {
        val st = _state.value
        if (st.semanticReady) return null
        val ds = st.dataset?.let { if (it.isFull) "الحزمة الكاملة" else "الكتب التسعة" } ?: "المتون"
        return when {
            st.dataset == null || st.dataset.isEmpty -> "ثبّت حزمة المتون أولًا"
            !st.semanticModel && !st.semanticVectors -> "لم يُثبَّت النموذج ولا المتّجهات"
            !st.semanticModel -> "النموذج غير مثبَّت"
            else -> "متّجهات $ds غير مثبَّتة"
        }
    }

    /** مقارنة الخادم بالمثبَّت: ناقص، أو بناء أحدث (الأحكام/الشروح بتاريخ البناء)، أو بصمة مختلفة (سائر الحزم) */
    fun computeDataUpdates() {
        val st = _state.value; val remote = st.pack.remote
        if (remote.isEmpty()) return
        val out = ArrayList<PackUpdate>()
        val corpusInstalled = st.dataset?.let { !it.isEmpty } ?: false
        val corpusId = st.dataset?.let { if (it.isFull) "corpus_full" else "corpus_tisa" }
        for (rp in remote) {
            when (rp.id) {
                "ahkam" -> {
                    val a = st.ahkam
                    // الخادم أحدث ممّا يفهمه هذا التطبيق: لا يمكن تثبيته، فلا يُنزَّل أبدًا (يلزم تحديث التطبيق) — يمنع حلقة التنزيل المتكرّر
                    val serverTooNew = rp.schema.isNotBlank() && rp.schema > MuhaddithDatabase.AHKAM_SCHEMA
                    val serverOld = (rp.schema.isNotBlank() && rp.schema < MuhaddithDatabase.AHKAM_SCHEMA) || (a.outdated && rp.sha256 != null && rp.sha256.equals(packs.installedSha("ahkam"), true))
                    if (serverTooNew) { if (!a.installed) out.add(PackUpdate(rp, "يلزم تحديث التطبيق لقراءة هذه الحزمة", false, auto = false)) }
                    else if (!a.installed) out.add(PackUpdate(rp, if (a.outdated) (if (serverOld) "الحزمة المثبَّتة قديمة والخادم لم يُحدَّث بعد" else "الحزمة المثبَّتة من إصدار قديم") else "غير مثبَّتة", false, auto = !serverOld))
                    else if (rp.built.isNotBlank() && a.built != null && rp.built > a.built) out.add(PackUpdate(rp, "بناء أحدث على الخادم (${ArabicText.arabicDigits(rp.built)} بدل ${ArabicText.arabicDigits(a.built)})", true))
                    else if (packs.installedSha("ahkam")?.let { h -> rp.sha256 != null && !h.equals(rp.sha256, true) } == true && !serverOld) out.add(PackUpdate(rp, "نسخة أحدث على الخادم (بصمة مختلفة)", true))
                }
                // حزمة الشروح: الحديثة اسمها shuruh3 (مخطط ٣)، والقديمة shuruh (مخطط ٢) للإصدارات السابقة — كلٌّ تأخذ ما يوافق مخطّطها فقط
                "shuruh3", "shuruh" -> {
                    val sh = st.shuruh
                    val key = rp.id
                    // التطبيق يقبل مخطط SHURUH_SCHEMA فقط: ما مخطّطه أقدم يُتجاهل (الإصدار السابق)، وما هو أحدث يُتجاهل مع تنبيه تحديث التطبيق
                    val serverTooNew = rp.schema.isNotBlank() && rp.schema > MuhaddithDatabase.SHURUH_SCHEMA
                    val serverTooOld = rp.schema.isNotBlank() && rp.schema < MuhaddithDatabase.SHURUH_SCHEMA
                    val serverStale = sh.outdated && rp.sha256 != null && rp.sha256.equals(packs.installedSha(key), true)
                    when {
                        serverTooOld -> { /* الإصدار السابق من الحزمة — لا يخصّ هذا التطبيق */ }
                        serverTooNew -> { if (!sh.installed) out.add(PackUpdate(rp, "يلزم تحديث التطبيق لقراءة هذه الحزمة", false, auto = false)) }
                        !sh.installed -> out.add(PackUpdate(rp, if (sh.outdated) (if (serverStale) "الحزمة المثبَّتة قديمة والخادم لم يُحدَّث بعد" else "الحزمة المثبَّتة من إصدار قديم") else "غير مثبَّتة", false, auto = !serverStale))
                        rp.built.isNotBlank() && sh.built != null && rp.built > sh.built -> out.add(PackUpdate(rp, "بناء أحدث على الخادم (${ArabicText.arabicDigits(rp.built)} بدل ${ArabicText.arabicDigits(sh.built)})", true))
                        packs.installedSha(key)?.let { h -> rp.sha256 != null && !h.equals(rp.sha256, true) } == true -> out.add(PackUpdate(rp, "نسخة أحدث على الخادم (بصمة مختلفة)", true))
                    }
                }
                "corpus_tisa", "corpus_full" -> if (corpusInstalled && rp.id == corpusId) {
                    val have = packs.installedSha(rp.id)
                    if (have != null && rp.sha256 != null && !have.equals(rp.sha256, true)) out.add(PackUpdate(rp, "نسخة أحدث من المتون على الخادم", true))
                }
                // البحث الدلالي: الناقص منه (النموذج أو متّجهات الحزمة المثبَّتة) يُنزَّل تلقائيًّا على الواي فاي، والمتجدّد يُعرض للتحديث
                "model_bge" -> if (st.semanticModel) {
                    val have = packs.installedSha(rp.id)
                    if (have != null && rp.sha256 != null && !have.equals(rp.sha256, true)) out.add(PackUpdate(rp, "نموذج أحدث على الخادم", true))
                } else if (corpusInstalled) out.add(PackUpdate(rp, "غير مثبَّت — يلزم للبحث الدلالي", false, auto = true))
                "vectors_tisa", "vectors_full" -> if (corpusId != null && rp.id == "vectors_" + corpusId.removePrefix("corpus_")) {
                    if (st.semanticVectors) {
                        val have = packs.installedSha(rp.id)
                        if (have != null && rp.sha256 != null && !have.equals(rp.sha256, true)) out.add(PackUpdate(rp, "متّجهات أحدث على الخادم", true))
                    } else out.add(PackUpdate(rp, "غير مثبَّتة — يلزم للبحث الدلالي في ${if (corpusId == "corpus_full") "الحزمة الكاملة" else "الكتب التسعة"}", false, auto = true))
                }
            }
        }
        _state.value = _state.value.copy(dataUpdates = out)
    }

    /** الصيانة التلقائية: تنزيل الأحكام والشروح إن كانت ناقصة أو قديمة أو تجدّدت على الخادم (بلا تدخّل) */
    private fun autoMaintain(force: Boolean = false) {
        if (force) { autoSuppressed = false; failedThisSession.clear() }   // «حدّث كل شيء» يُلغي الكبح ويعيد المحاولة
        if (autoSuppressed) return   // أوقف المستخدم التنزيل يدويًّا
        val st = _state.value
        if (!st.settings.autoDataUpdate && !force) return
        if (st.dataset == null || st.dataset.isEmpty) return
        if (!PackWorker.isOnline(getApplication()) || (st.settings.wifiOnly && !PackWorker.onWifi(getApplication()))) return
        if (PackWorker.isBusy() || pending.isNotEmpty()) return
        // كل ما على الخادم أحدث يُنزَّل؛ ما فشل تثبيته هذه الجلسة لا يُعاد (يمنع الحلقة)؛ الحزم الكبيرة (> ١٠٠ م.ب) على الواي فاي فقط إلا بأمر المستخدم
        val wifi = PackWorker.onWifi(getApplication())
        val list = st.dataUpdates.filter { (it.auto || force) && it.pack.id !in failedThisSession && (force || wifi || it.pack.sizeGz <= 100_000_000L) }
            .sortedBy { listOf("corpus_tisa", "corpus_full", "ahkam", "shuruh3", "shuruh", "model_bge", "vectors_tisa", "vectors_full").indexOf(it.pack.id) }.map { it.pack }
        if (list.isEmpty()) return
        packs.log("تنزيل تلقائي: " + list.joinToString("، ") { it.name })
        _state.value = _state.value.copy(toast = "يُنزَّل تلقائيًّا: " + list.joinToString("، ") { it.name })
        downloadPacks(list, auto = true)
    }

    /** الحزم التي تلزم للمجموعة الموصى بها: المتون (التسعة أو الكاملة) + الأحكام + الشروح، بلا ما هو مثبَّت */
    fun recommendedPacks(full: Boolean): List<RemotePack> {
        val st = _state.value; val remote = st.pack.remote
        val corpusId = if (full) "corpus_full" else "corpus_tisa"
        val haveCorpus = st.dataset?.let { !it.isEmpty && it.isFull == full } ?: false
        val want = listOfNotNull(
            if (haveCorpus) null else corpusId,
            if (st.ahkam.installed && !st.ahkam.outdated) null else "ahkam",
            if (st.shuruh.installed) null else "shuruh"
        )
        return want.mapNotNull { id -> remote.firstOrNull { it.id == id } }
    }

    fun importPack(uris: List<Uri>) = runPack("جارٍ استيراد الحزمة…") { cb -> packs.importFrom(uris, cb) }

    fun importFound(fp: FoundPack) = runPack("جارٍ تفعيل «${fp.pack.name}» من ملفات الهاتف…") { cb -> packs.importFound(fp, cb) }

    /** يمسح مجلدًا اختاره المستخدم بحثًا عن أجزاء الحزم */
    fun scanFolder(tree: Uri, name: String?, persist: Boolean = true) {
        if (persist) updateSettings { it.copy(dataFolderUri = tree.toString()) }
        _state.value = _state.value.copy(pack = _state.value.pack.copy(scanning = true, folderName = name))
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { try { packs.scanFolder(tree) } catch (_: Exception) { emptyList() } }
            _state.value = _state.value.copy(pack = _state.value.pack.copy(found = found, scanning = false))
        }
    }

    /** ملفات وصلت بالمشاركة من تطبيق آخر (مدير الملفات مثلًا) */
    fun onSharedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // انتظر جاهزية القاعدة
            while (!_state.value.ready) kotlinx.coroutines.delay(100)
            importPack(uris)
        }
    }

    fun deletePartial() {
        packs.deletePartial()
        _state.value = _state.value.copy(pack = _state.value.pack.copy(partialBytes = 0))
    }

    /** عند مغادرة التطبيق: تحرير نموذج الدلالي من الذاكرة إن طُلب */
    private var lastCheck = 0L
    /** عند كل تشغيل أو عودة إلى الواجهة: فحص الخادم (مرة كل ساعة على الأكثر) ثم تطبيق ما يلزم تلقائيًّا */
    fun onForeground() {
        if (System.currentTimeMillis() - lastCheck < 60 * 60 * 1000L) return
        if (!::packs.isInitialized) return
        lastCheck = System.currentTimeMillis()
        checkForUpdate(silent = true)
    }

    /** بأمر المستخدم: افحص الخادم وطبّق كل التحديثات (التطبيق والحزم) */
    fun updateEverything() {
        lastCheck = System.currentTimeMillis()
        checkForUpdate(silent = false, applyAll = true)
    }

    fun onBackground() {
        if (_state.value.settings.releaseModelInBackground && !PackWorker.isBusy()) {
            viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { repo.semantic.release() } } }
        }
    }

    fun removeSemantic() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.semantic.release(); packs.removeSemantic() }
            _state.value = _state.value.copy(toast = "أُزيلت حزم البحث الدلالي.")
            refreshAll()
        }
    }

    fun removeInstalledPack() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { packs.removeInstalled() }
            _state.value = _state.value.copy(pack = _state.value.pack.copy(message = "أُزيلت الحزمة من الهاتف."), searched = false, results = emptyList())
            refreshAll()
        }
    }

    private val pending = ArrayDeque<Pair<String, ((Long, Long, Int) -> Unit) -> Unit>>()

    private fun runPack(msg: String, work: ((Long, Long, Int) -> Unit) -> Unit) {
        if (PackWorker.isBusy()) {
            pending.addLast(msg to work)
            _state.value = _state.value.copy(toast = "عملية جارية الآن — أُضيف إلى قائمة الانتظار وسيبدأ بعدها تلقائيًّا", queued = pending.map { it.first })
            return
        }
        PackWorker.wifiOnly = _state.value.settings.wifiOnly
        _state.value = _state.value.copy(searched = false, results = emptyList())
        PackWorker.start(getApplication(), msg, work)
    }

    /** يشغّل التالي في الطابور بعد انتهاء عمل */
    private fun runNextPending() {
        val next = pending.removeFirstOrNull() ?: return
        _state.value = _state.value.copy(queued = pending.map { it.first })
        PackWorker.wifiOnly = _state.value.settings.wifiOnly
        PackWorker.start(getApplication(), next.first, next.second)
    }

    fun packLogRefresh() { _state.value = _state.value.copy(packLog = runCatching { packs.logLines() }.getOrDefault(emptyList())) }
    fun clearPackLog() { packs.clearLog(); packLogRefresh() }
}

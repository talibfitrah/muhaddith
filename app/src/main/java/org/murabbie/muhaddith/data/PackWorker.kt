package org.murabbie.muhaddith.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** حالة عملية تحميل/استيراد جارية — مشتركة بين الشاشة وخدمة الخلفية */
data class WorkState(
    val busy: Boolean = false,
    val title: String = "",
    val done: Long = 0,
    val total: Long = -1,
    val phase: Int = 0,           // ٠ تنزيل، ١ تحقّق، ٢ فكّ ضغط/تثبيت، ٣ نقل
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val message: String? = null,
    val error: String? = null,
    val finishedAt: Long = 0
)

/**
 * ينفّذ أعمال الحزم في نطاق يعيش مع العملية لا مع الشاشة، ويشغّل خدمة أمامية بإشعار تقدّم
 * حتى لا يوقفها النظام إذا غادر المستخدم التطبيق.
 */
object PackWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WorkState())
    val state: StateFlow<WorkState> = _state
    @Volatile private var job: Job? = null
    @Volatile private var cancelRequested = false
    @Volatile var wifiOnly = false
    @Volatile var onDone: (() -> Unit)? = null

    fun isBusy() = _state.value.busy

    fun cancel() { cancelRequested = true; _state.value = _state.value.copy(message = "جارٍ الإيقاف…") }
    /** تغيير عنوان العمل الجاري (لتنزيل عدة حزم متتابعة) */
    fun setTitle(t: String) { if (_state.value.busy) _state.value = _state.value.copy(title = t, message = t, done = 0, total = -1, phase = 0) }

    /** رسالة توقّف إن وجب التوقف الآن (إلغاء أو شرط الواي فاي)، وإلا null */
    fun gate(ctx: Context): String? {
        if (cancelRequested) return "أُلغي التنزيل — يمكنك استئنافه لاحقًا من حيث توقّف"
        if (wifiOnly && !onWifi(ctx)) return "توقّف: التنزيل مقيَّد بالواي فاي (غيّر ذلك من الإعدادات)"
        return null
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** يبدأ عملًا؛ work يستقبل مُبلّغ التقدّم (done, total, phase) */
    fun start(ctx: Context, title: String, work: ((Long, Long, Int) -> Unit) -> Unit) {
        if (isBusy()) return
        cancelRequested = false
        val app = ctx.applicationContext
        _state.value = WorkState(busy = true, title = title, message = title)
        try { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, PackService::class.java)) } catch (_: Exception) { }
        job = scope.launch {
            var lastT = System.currentTimeMillis(); var lastD = 0L; var speed = 0L
            val err = try {
                work { done, total, phase ->
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 1000) {
                        speed = (done - lastD) * 1000 / (now - lastT); lastT = now; lastD = done
                    }
                    val eta = if (speed > 0 && total > done) (total - done) / speed else -1
                    _state.value = _state.value.copy(done = done, total = total, phase = phase, speedBps = speed, etaSec = eta)
                    PackService.update(app, _state.value)
                }
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            _state.value = WorkState(
                busy = false, title = title, error = err,
                message = if (err == null) "اكتمل التنزيل والتثبيت — انظر «حالة البيانات» أعلاه." else null,
                finishedAt = System.currentTimeMillis()
            )
            PackService.finish(app, err == null, err)
            onDone?.invoke()
        }
    }
}

/** خدمة أمامية تعرض تقدّم التحميل وتُبقي العملية حيّة */
class PackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = build(this, PackWorker.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        if (!PackWorker.isBusy()) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "muhaddith_packs"
        const val NOTIF_ID = 41

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "تحميل حزم البيانات", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "تقدّم تنزيل الحزم واستيرادها"
                    })
                }
            }
        }

        fun build(ctx: Context, s: WorkState): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, org.murabbie.muhaddith.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val phase = when (s.phase) { 0 -> "تنزيل"; 1 -> "تحقّق"; 2 -> "تثبيت"; else -> "نقل" }
            val text = if (s.total > 0) "$phase · ${s.done / 1_000_000} / ${s.total / 1_000_000} م.ب" + (if (s.speedBps > 0) " · ${s.speedBps / 1000} ك.ب/ث" else "") else phase
            val b = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(s.title.ifBlank { "المحدِّث" })
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            if (s.total > 0) b.setProgress(1000, ((s.done * 1000) / s.total).toInt().coerceIn(0, 1000), false)
            else b.setProgress(0, 0, true)
            return b.build()
        }

        fun update(ctx: Context, s: WorkState) {
            if (!hasNotifPermission(ctx)) return
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(ctx, s))
        }

        fun finish(ctx: Context, ok: Boolean, err: String?) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
            if (hasNotifPermission(ctx)) {
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, org.murabbie.muhaddith.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                nm.notify(NOTIF_ID + 1, androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setContentTitle(if (ok) "اكتمل تحميل الحزمة" else "توقّف التحميل")
                    .setContentText(if (ok) "المحدِّث جاهز للبحث بلا إنترنت." else (err ?: ""))
                    .setContentIntent(open).setAutoCancel(true).build())
            }
            ctx.stopService(Intent(ctx, PackService::class.java))
        }

        fun hasNotifPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

package org.murabbie.muhaddith.data

import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * مصدر حزم على الشبكة: مجلد ويب عادي (فيه manifest.json والأجزاء بجواره)،
 * أو رابط مشاركة مجلد على NAS سينولوجي مثل http://nas.example:5000/sharing/XXXX/muhaddith
 *
 * روابط سينولوجي لا تُعطي الملفات مباشرة؛ يلزم فتح صفحة المشاركة أولًا (لأخذ الكعكة) ثم طلب الملف
 * بأحد أشكال العناوين التي تعرفها DSM. نجرّبها بالترتيب ونحفظ أول شكل ينجح.
 * خالٍ من أي اعتماد على أندرويد ليمكن اختباره على JVM.
 */
class PackSource(base: String) {

    /** رابط مشاركة سينولوجي مفكَّك: الخادم، ومعرّف المشاركة، والمجلد الفرعي داخلها (قد يكون فارغًا) */
    data class Syno(val host: String, val id: String, val sub: String) {
        val page: String get() = "$host/sharing/$id"
    }

    companion object {
        private val SYNO_RX = Regex("""^(https?://[^/]+)/sharing/([A-Za-z0-9_\-]+)/?(.*?)/?$""")
        /** عنوان تنزيل مباشر من مشاركة ملف: http://host/fsdownload/<id>/<name> — يحتاج كعكة صفحة المشاركة /sharing/<id> */
        private val FSDL_RX = Regex("""^(https?://[^/]+)/fsdownload/([A-Za-z0-9_\-]+)/.+$""")
        const val USER_AGENT = "Muhaddith-Android/0.8"

        fun parseSyno(base: String): Syno? {
            val m = SYNO_RX.find(base.trim()) ?: return null
            return Syno(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim('/'))
        }

        /** تفعيل حفظ الكعكات لكل اتصالات HttpURLConnection في التطبيق (مرة واحدة) */
        fun ensureCookies() {
            if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** أشكال عناوين تنزيل ملف من مشاركة مجلد على DSM، بالترتيب الأرجح */
        fun synoCandidates(s: Syno, name: String): List<String> {
            val rel = if (s.sub.isEmpty()) name else "${s.sub}/$name"
            val path = "/$rel"
            val dl = "api=SYNO.FolderSharing.Download&version=2&method=download&mode=download&stdhtml=false" +
                "&dlname=${enc("\"$name\"")}&path=${enc("[\"$path\"]")}&_sharing_id=${enc("\"${s.id}\"")}"
            val out = ArrayList<String>()
            out.add("${s.host}/fsdownload/${s.id}/$rel")
            out.add("${s.host}/fsdownload/webapi/entry.cgi?$dl")
            out.add("${s.host}/sharing/webapi/entry.cgi?$dl")
            if (s.sub.isNotEmpty()) out.add("${s.host}/fsdownload/${s.id}/$name")
            return out
        }
    }

    val base: String = base.trim().let { if (it.endsWith("/")) it else "$it/" }
    val syno: Syno? = parseSyno(base)
    val isSyno: Boolean get() = syno != null

    /** الشكل الذي نجح آخر مرة (فهرس في synoCandidates) ليُجرَّب أولًا */
    @Volatile private var working = -1
    /** صفحات المشاركة التي فُتحت لأخذ كعكتها (معرّف المشاركة) */
    private val primed = HashSet<String>()

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** فتح صفحة المشاركة مرة لتحصيل كعكة الجلسة التي تطلبها DSM قبل التنزيل (لكل معرّف مشاركة) */
    private fun prime(host: String, id: String) {
        synchronized(primed) { if (!primed.add(id)) return }
        ensureCookies()
        try {
            val c = open(URL("$host/sharing/$id"))
            try { c.responseCode; c.inputStream.use { it.skip(1 shl 16) } } catch (_: Exception) { } finally { c.disconnect() }
        } catch (_: Exception) { }
    }

    /** إن كان العنوان تنزيلًا مباشرًا من مشاركة سينولوجي فافتح صفحتها أولًا */
    private fun primeFor(url: String) {
        val m = FSDL_RX.find(url) ?: return
        prime(m.groupValues[1], m.groupValues[2])
    }

    /** عناوين مرشَّحة لملف باسمه (أو عنوانه الكامل) */
    fun candidates(name: String): List<String> {
        if (name.startsWith("http://") || name.startsWith("https://")) return listOf(name)
        val s = syno ?: return listOf(base + name)
        val all = synoCandidates(s, name)
        return if (working in all.indices) listOf(all[working]) + all.filterIndexed { i, _ -> i != working } else all
    }

    /**
     * يفتح اتصالًا بأول عنوان يستجيب استجابة صالحة (ليست صفحة HTML ولا رسالة خطأ JSON لملف ثنائي).
     * [rangeFrom] > 0 يطلب استئنافًا؛ الخادم قد يتجاهله (200) أو يرفضه (416) ويعالج المستدعي ذلك.
     */
    fun connect(name: String, binary: Boolean, rangeFrom: Long = 0): HttpURLConnection {
        val s = syno
        if (s != null && !name.startsWith("http")) prime(s.host, s.id)
        val urls = candidates(name)
        var last: Exception? = null
        for ((i, u) in urls.withIndex()) {
            primeFor(u)
            val c = try { open(URL(u)) } catch (e: Exception) { last = e; continue }
            if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
            try {
                val code = c.responseCode
                val ct = (c.contentType ?: "").lowercase()
                val ok = (code in 200..299 || code == 416) && !ct.contains("text/html") && !(binary && ct.contains("json"))
                if (ok) {
                    if (s != null && urls.size > 1) working = synoCandidates(s, name).indexOf(u)
                    return c
                }
                last = IllegalStateException(if (code in 200..299) "استجابة غير متوقعة ($ct) عند طلب $name" else "HTTP $code عند طلب $name")
            } catch (e: Exception) { last = e }
            c.disconnect()
        }
        throw last ?: IllegalStateException("تعذّر الوصول إلى $name")
    }
}

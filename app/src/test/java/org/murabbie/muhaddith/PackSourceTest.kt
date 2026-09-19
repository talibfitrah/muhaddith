package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.PackFetcher
import org.murabbie.muhaddith.data.PackSource
import org.murabbie.muhaddith.data.RemotePack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/** محاكاة مشاركة مجلد سينولوجي: صفحة تعطي كعكة، وتنزيل لا يعمل إلا بالكعكة، وأشكال عناوين مختلفة */
class PackSourceTest {

    private lateinit var server: ServerSocket
    private val files = HashMap<String, ByteArray>()
    @Volatile private var mode = "fsdownload"   // أي شكل عنوان يخدم الملفات: fsdownload | webapi | plain
    private val log = ArrayList<String>()

    @Before fun setUp() {
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(sock) }
            }
        }
    }
    @After fun tearDown() { server.close() }

    private fun host() = "http://127.0.0.1:${server.localPort}"

    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            var cookie: String? = null
            while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Cookie:", true)) cookie = l.substringAfter(':').trim() }
            val target = requestLine.split(' ')[1]
            synchronized(log) { log.add(target) }
            val out = s.getOutputStream()
            fun send(status: String, type: String, body: ByteArray, extra: String = "") {
                out.write("HTTP/1.0 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
            }
            val html = "<html><body>share page</body></html>".toByteArray()
            if (target.startsWith("/sharing/ABC")) return send("200 OK", "text/html; charset=utf-8", html, "Set-Cookie: sharing_sid=xyz; Path=/\r\n")
            if (mode != "plain" && (cookie == null || !cookie.contains("sharing_sid=xyz"))) return send("200 OK", "text/html", html)  // DSM تعيد صفحة الدخول بلا كعكة
            val name: String? = when {
                mode == "fsdownload" && target.startsWith("/fsdownload/ABC/muhaddith/") -> target.substringAfterLast('/')
                mode == "webapi" && target.startsWith("/fsdownload/webapi/entry.cgi?") -> {
                    val q = target.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    if (q["api"] == "SYNO.FolderSharing.Download" && q["_sharing_id"] == "\"ABC\"") q["path"]!!.trim('[', ']', '"').substringAfterLast('/') else null
                }
                mode == "plain" && target.startsWith("/plain/") -> target.substringAfterLast('/')
                else -> null
            }
            if (mode == "fsdownload" && target.startsWith("/fsdownload/webapi/")) return send("200 OK", "application/json", "{\"success\":false}".toByteArray())
            val data = name?.let { files[it] } ?: return send("404 Not Found", "text/plain", ByteArray(0))
            send("200 OK", "application/octet-stream", data)
        }
    }

    @Test fun parsesSynologyShareLinks() {
        val s = PackSource.parseSyno("http://nas.example:5000/sharing/ehniFEwJg/muhaddith/")!!
        assertEquals("http://nas.example:5000", s.host); assertEquals("ehniFEwJg", s.id); assertEquals("muhaddith", s.sub)
        val r = PackSource.parseSyno("https://nas.example/sharing/XyZ")!!
        assertEquals("", r.sub)
        assertNull(PackSource.parseSyno("https://muhaddith.murabbie.org/android/"))
        val c = PackSource.synoCandidates(s, "manifest.json")
        assertEquals("http://nas.example:5000/fsdownload/ehniFEwJg/muhaddith/manifest.json", c[0])
        assertTrue(c[1].contains("SYNO.FolderSharing.Download") && c[1].contains("%2Fmuhaddith%2Fmanifest.json"))
    }

    @Test fun downloadsThroughSharePageCookieAndFirstPattern() {
        files["manifest.json"] = "{\"packs\":[]}".toByteArray()
        val src = PackSource("${host()}/sharing/ABC/muhaddith")
        val c = src.connect("manifest.json", binary = false)
        assertEquals("{\"packs\":[]}", c.inputStream.bufferedReader().readText())
        assertTrue(log.first().startsWith("/sharing/ABC"))
    }

    @Test fun fallsBackToWebApiPatternAndFetcherVerifiesSha() {
        mode = "webapi"
        val data = ByteArray(200_000) { (it * 7).toByte() }
        files["muhaddith_x.db.gz.001"] = data.copyOfRange(0, 100_000); files["muhaddith_x.db.gz.002"] = data.copyOfRange(100_000, 200_000)
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val dir = File.createTempFile("muhaddith", "").apply { delete(); mkdirs() }
        val f = PackFetcher(dir, "${host()}/sharing/ABC/muhaddith/")
        val pack = RemotePack("x", "x", "", "muhaddith_x.db.gz", data.size.toLong(), 0, sha, 0, 0, listOf("muhaddith_x.db.gz.001", "muhaddith_x.db.gz.002"))
        val out = f.fetch(pack) { _, _, _ -> }
        assertEquals(2, out.size); assertEquals(200_000L, out.sumOf { it.length() })
        // الجزء الثاني جرّب الشكل الناجح مباشرة (لا محاولة fsdownload/ABC قبله)
        val second = synchronized(log) { log.dropWhile { !it.contains("001") }.drop(1).first { it.contains("002") } }
        assertTrue(second, second.startsWith("/fsdownload/webapi/"))
    }

    @Test fun plainWebFolderStillWorks() {
        mode = "plain"
        files["manifest.json"] = "{}".toByteArray()
        val c = PackSource("${host()}/plain/").connect("manifest.json", binary = false)
        assertEquals("{}", c.inputStream.bufferedReader().readText())
    }
}

package org.murabbie.muhaddith.data

import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * خادم HTTP مصغّر يشارك الحزمة المثبَّتة على هذا الجهاز مع جهاز آخر على الشبكة نفسها
 * (هاتف ↔ تابلت) بالصيغة ذاتها التي ينزّل بها التطبيق من الإنترنت: manifest.json + الملف، مع دعم Range.
 */
object ShareServer {
    const val PORT = 8765
    @Volatile private var server: ServerSocket? = null
    @Volatile var running = false
    @Volatile var sha256: String? = null
    @Volatile var hashing = false
    @Volatile var served = 0L

    fun ip(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
        } catch (_: Exception) { null }
    }

    fun url(): String? = ip()?.let { "http://$it:$PORT/" }

    fun start(corpus: File, meta: DatasetInfo) {
        if (running) return
        val ss = ServerSocket(PORT)
        server = ss; running = true; served = 0
        sha256 = null; hashing = true
        thread(isDaemon = true) {
            // بصمة الملف تُحسب مرة واحدة ليتحقّق الجهاز الآخر منها
            try {
                val md = MessageDigest.getInstance("SHA-256")
                corpus.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                sha256 = md.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { } finally { hashing = false }
        }
        thread(isDaemon = true) {
            while (running) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { handle(sock, corpus, meta) } catch (_: Exception) { } }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    private fun handle(sock: Socket, corpus: File, meta: DatasetInfo) {
        sock.soTimeout = 60_000
        sock.use { s ->
            val input = s.getInputStream().bufferedReader()
            val line = input.readLine() ?: return
            var range: String? = null
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
            val path = line.split(' ').getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            when {
                path.endsWith("manifest.json") -> {
                    val body = manifest(corpus, meta).toByteArray()
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body); out.flush()
                }
                path.endsWith("muhaddith_corpus.db") -> {
                    val len = corpus.length()
                    var start = 0L
                    if (range != null) {
                        start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                        if (start >= len) { out.write("HTTP/1.0 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return }
                    }
                    val remaining = len - start
                    val status = if (start > 0) "206 Partial Content" else "200 OK"
                    val extra = if (start > 0) "Content-Range: bytes $start-${len - 1}/$len\r\n" else ""
                    out.write(("HTTP/1.0 $status\r\nContent-Type: application/octet-stream\r\nContent-Length: $remaining\r\nAccept-Ranges: bytes\r\n${extra}Connection: close\r\n\r\n").toByteArray())
                    RandomAccessFile(corpus, "r").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(1 shl 16)
                        var left = remaining
                        while (left > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
                            out.write(buf, 0, n); left -= n; served += n
                        }
                    }
                    out.flush()
                }
                else -> {
                    val body = "المحدِّث — خادم مشاركة الحزمة. أدخل هذا العنوان في التطبيق على الجهاز الآخر.".toByteArray()
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body); out.flush()
                }
            }
        }
    }

    private fun manifest(corpus: File, meta: DatasetInfo): String {
        val sha = sha256?.let { "\"sha256_gz\": \"$it\"," } ?: ""
        return """{"version":1,"packs":[{"id":"shared","name":"${meta.name} (من الجهاز الآخر)","description":"منقولة عبر الشبكة المحلية من جهاز مجاور","file":"muhaddith_corpus.db","size_gz":${corpus.length()},"size_db":${corpus.length()},$sha"hadiths":${meta.hadiths},"books":${meta.books}}]}"""
    }
}

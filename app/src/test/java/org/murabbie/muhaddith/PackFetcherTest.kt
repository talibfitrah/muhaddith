package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.PackFetcher
import org.murabbie.muhaddith.data.RemotePack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.security.MessageDigest
import kotlin.random.Random

/** خادم محلي يدعم Range ويمكن جعله يقطع الاتصال بعد عدد من البايتات */
class PackFetcherTest {

    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val files = HashMap<String, ByteArray>()
    @Volatile private var cutAfter: Long = -1     // اقطع بعد هذا العدد من البايتات في كل طلب
    @Volatile private var ignoreRange = false     // تصرّف كخادم لا يدعم Range
    @Volatile private var requests = 0

    @Before fun setUp() {
        dir = File.createTempFile("muhaddith", "").apply { delete(); mkdirs() }
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(sock) }
            }
        }
    }

    /** خادم HTTP/1.0 مصغّر يدعم Range */
    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            requests++
            val name = requestLine.split(' ')[1].substringAfterLast('/')
            val out = s.getOutputStream()
            val data = files[name]
            if (data == null) { out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return }
            var start = 0L
            val headers = StringBuilder()
            if (range != null && !ignoreRange) {
                start = range.removePrefix("bytes=").substringBefore('-').toLong()
                if (start >= data.size) { out.write("HTTP/1.0 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return }
                headers.append("Content-Range: bytes $start-${data.size - 1}/${data.size}\r\n")
            }
            val body = data.copyOfRange(start.toInt(), data.size)
            val status = if (start > 0) "206 Partial Content" else "200 OK"
            out.write(("HTTP/1.0 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$headers\r\n").toByteArray())
            val limit = if (cutAfter >= 0) minOf(cutAfter, body.size.toLong()).toInt() else body.size
            out.write(body, 0, limit)
            out.flush()
            // إن قطعنا فأغلق المقبس فجأة ليصل للعميل EOF قبل اكتمال Content-Length
        }
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun base() = "http://127.0.0.1:${server.localPort}/"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun bytes(n: Int) = Random(n).nextBytes(n)

    @Test fun downloadsSingleFileAndVerifies() {
        val data = bytes(3_000_000)
        files["pack.db.gz"] = data
        val pack = RemotePack("p", "p", "", "pack.db.gz", data.size.toLong(), 0, sha(data), 0, 0)
        val out = PackFetcher(dir, base()).fetch(pack) { _, _, _ -> }
        assertEquals(1, out.size)
        assertTrue(out[0].readBytes().contentEquals(data))
    }

    @Test fun resumesAfterConnectionCut() {
        val data = bytes(5_000_000)
        files["pack.db.gz"] = data
        val pack = RemotePack("p", "p", "", "pack.db.gz", data.size.toLong(), 0, sha(data), 0, 0)
        val f = PackFetcher(dir, base())
        cutAfter = 1_200_000
        try { f.fetch(pack) { _, _, _ -> }; fail("كان يجب أن ينقطع") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("انقطع"))
        }
        assertEquals(1_200_000L, f.partialBytes())
        cutAfter = 2_000_000       // ينقطع مرة ثانية ثم يكمل
        try { f.fetch(pack) { _, _, _ -> }; fail() } catch (e: IllegalStateException) { }
        assertEquals(3_200_000L, f.partialBytes())
        cutAfter = -1
        val out = f.fetch(pack) { _, _, _ -> }
        assertTrue(out[0].readBytes().contentEquals(data))
        assertTrue("يجب أن يُستأنف لا أن يُعاد", requests <= 4)
    }

    @Test fun restartsCleanlyWhenServerIgnoresRange() {
        val data = bytes(2_000_000)
        files["pack.db.gz"] = data
        val pack = RemotePack("p", "p", "", "pack.db.gz", data.size.toLong(), 0, sha(data), 0, 0)
        val f = PackFetcher(dir, base())
        cutAfter = 500_000
        try { f.fetch(pack) { _, _, _ -> } } catch (_: IllegalStateException) { }
        cutAfter = -1; ignoreRange = true
        val out = f.fetch(pack) { _, _, _ -> }
        assertTrue(out[0].readBytes().contentEquals(data))
    }

    @Test fun downloadsPartsInOrderAndVerifiesWholeHash() {
        val whole = bytes(4_000_000)
        val p1 = whole.copyOfRange(0, 1_500_000); val p2 = whole.copyOfRange(1_500_000, 3_000_000); val p3 = whole.copyOfRange(3_000_000, whole.size)
        files["full.db.gz.001"] = p1; files["full.db.gz.002"] = p2; files["full.db.gz.003"] = p3
        val pack = RemotePack("f", "f", "", "full.db.gz", whole.size.toLong(), 0, sha(whole), 0, 0,
            parts = listOf("full.db.gz.001", "full.db.gz.002", "full.db.gz.003"))
        val f = PackFetcher(dir, base())
        cutAfter = 1_000_000
        try { f.fetch(pack) { _, _, _ -> }; fail() } catch (_: IllegalStateException) { }
        cutAfter = -1
        val out = f.fetch(pack) { _, _, _ -> }
        assertEquals(3, out.size)
        val joined = out.flatMap { it.readBytes().toList() }.toByteArray()
        assertTrue(joined.contentEquals(whole))
    }

    @Test fun rejectsCorruptedDownload() {
        val data = bytes(1_000_000)
        files["pack.db.gz"] = data
        val pack = RemotePack("p", "p", "", "pack.db.gz", data.size.toLong(), 0, sha(bytes(999)), 0, 0)
        val f = PackFetcher(dir, base())
        try { f.fetch(pack) { _, _, _ -> }; fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("تالف")) }
        assertEquals(0L, f.partialBytes())
    }

    @Test fun reportsProgressUpToTotal() {
        val data = bytes(2_500_000)
        files["pack.db.gz"] = data
        val pack = RemotePack("p", "p", "", "pack.db.gz", data.size.toLong(), 0, null, 0, 0)
        var last = 0L; var total = 0L
        PackFetcher(dir, base()).fetch(pack) { d, t, _ -> last = d; total = t }
        assertEquals(data.size.toLong(), last); assertEquals(data.size.toLong(), total)
    }
}

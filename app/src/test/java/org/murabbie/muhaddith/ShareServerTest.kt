package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.DatasetInfo
import org.murabbie.muhaddith.data.PackFetcher
import org.murabbie.muhaddith.data.RemotePack
import org.murabbie.muhaddith.data.ShareServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL
import kotlin.random.Random

/** خادم المشاركة بين جهازين: البيان، والتنزيل مع الاستئناف، والبصمة */
class ShareServerTest {
    private val dir = File.createTempFile("share", "").apply { delete(); mkdirs() }
    @After fun tearDown() { ShareServer.stop(); dir.deleteRecursively() }

    @Test fun endToEndOverLoopback() {
        val data = Random(11).nextBytes(3_500_000)
        val corpus = File(dir, "corpus.db").apply { writeBytes(data) }
        val meta = DatasetInfo("الكتب التسعة", 58684, 9, 49845, false, data.size.toLong())
        ShareServer.start(corpus, meta)
        Thread.sleep(300)                       // حتى تُحسب البصمة
        val base = "http://127.0.0.1:${ShareServer.PORT}/"
        val text = URL(base + "manifest.json").readText()
        fun field(k: String) = Regex("\"$k\"\\s*:\\s*\"?([^,\"}]+)").find(text)?.groupValues?.get(1)
        assertEquals("muhaddith_corpus.db", field("file"))
        assertEquals(data.size.toLong(), field("size_gz")!!.toLong())
        assertEquals(58684, field("hadiths")!!.toInt())
        val sha = field("sha256_gz")
        assertTrue("البصمة يجب أن تكون في البيان", sha != null && sha.length == 64)
        val pack = RemotePack("shared", "x", "", "muhaddith_corpus.db", data.size.toLong(), data.size.toLong(), sha, 58684, 9)

        // تنزيل كامل ثم استئناف من منتصف الملف
        val out = File(dir, "dl").apply { mkdirs() }
        val f = PackFetcher(out, base)
        val files = f.fetch(pack) { _, _, _ -> }
        assertTrue(files[0].readBytes().contentEquals(data))
        f.discardAll()
        val part = f.partFile("muhaddith_corpus.db")
        part.writeBytes(data.copyOfRange(0, 1_000_000))       // نصف تنزيل سابق
        val files2 = f.fetch(pack) { _, _, _ -> }
        assertTrue(files2[0].readBytes().contentEquals(data))
        assertTrue(ShareServer.served >= data.size.toLong() + 2_500_000)
    }
}

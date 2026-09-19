package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.KnownPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownPacksTest {
    @Test fun identifiesPlainPartNames() {
        val (p, n) = KnownPacks.identify("muhaddith_corpus_full.db.gz.017")!!
        assertEquals("corpus_full", p.id); assertEquals(17, n)
        val (t, m) = KnownPacks.identify("muhaddith_corpus_tisa.db.gz.003")!!
        assertEquals("corpus_tisa", t.id); assertEquals(3, m)
    }

    @Test fun toleratesBrowserRenames() {
        assertEquals(4, KnownPacks.identify("muhaddith_corpus_full.db.gz.004 (1)")!!.second)
        assertEquals(2, KnownPacks.identify("muhaddith_corpus_tisa.db.gz.002-1")!!.second)
        assertEquals(20, KnownPacks.identify("download_muhaddith_corpus_full.db.gz.020.bin")!!.second)
    }

    @Test fun rejectsOutOfRangeAndForeignNames() {
        assertNull(KnownPacks.identify("muhaddith_corpus_tisa.db.gz.004"))
        assertNull(KnownPacks.identify("muhaddith_corpus_full.db.gz.021"))
        assertNull(KnownPacks.identify("muhaddith_corpus_full.db.gz"))
        assertNull(KnownPacks.identify("photo.jpg"))
    }

    @Test fun partNamesMatchManifest() {
        val full = KnownPacks.ALL.first { it.id == "corpus_full" }
        assertEquals("muhaddith_corpus_full.db.gz.001", full.partName(1))
        assertEquals("muhaddith_corpus_full.db.gz.020", full.partName(20))
    }

    @Test fun identifiesAhkamPack() {
        val ahkam = KnownPacks.ALL.first { it.id == "ahkam" }
        assertEquals("ahkam", ahkam.kind)
        val (p, n) = KnownPacks.identify("muhaddith_ahkam.db.gz.001 (2)")!!
        assertEquals("ahkam", p.id); assertEquals(1, n)
        assertNull(KnownPacks.identify("muhaddith_ahkam.db.gz.%03d".format(ahkam.partCount + 1)))
        // الترتيب عند التثبيت: المتون ثم الأحكام ثم النموذج ثم المتجهات
        val order = listOf("corpus", "ahkam", "model", "vectors")
        val sorted = KnownPacks.ALL.sortedBy { order.indexOf(it.kind) }.map { it.kind }
        assertEquals(sorted, sorted.sortedBy { order.indexOf(it) })
    }
}

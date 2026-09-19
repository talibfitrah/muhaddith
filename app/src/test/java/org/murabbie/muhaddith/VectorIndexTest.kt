package org.murabbie.muhaddith

import org.murabbie.muhaddith.semantic.VectorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VectorIndexTest {
    @Test fun topKMatchesReferenceComputation() {
        val tmp = File.createTempFile("vec", ".bin")
        javaClass.getResourceAsStream("/vectors_fixture.bin")!!.use { it.copyTo(tmp.outputStream()) }
        val json = javaClass.getResourceAsStream("/vectors_fixture.json")!!.bufferedReader().readText()
        val query = Regex("\"query\":\\s*\\[([^\\]]*)\\]").find(json)!!.groupValues[1].split(",").map { it.trim().toFloat() }.toFloatArray()
        val topIds = Regex("\"top_ids\":\\s*\\[([^\\]]*)\\]").find(json)!!.groupValues[1].split(",").map { it.trim().toLong() }
        val topScores = Regex("\"top_scores\":\\s*\\[([^\\]]*)\\]").find(json)!!.groupValues[1].split(",").map { it.trim().toFloat() }
        val idx = VectorIndex.open(tmp)
        assertEquals(384, idx.dim); assertEquals(500, idx.count); assertEquals("test", idx.dataset)
        val hits = idx.search(query, 5)
        assertEquals(topIds, hits.map { it.id })
        for (i in hits.indices) assertTrue("score $i", Math.abs(hits[i].score - topScores[i]) < 1e-3)
        tmp.delete()
    }
}

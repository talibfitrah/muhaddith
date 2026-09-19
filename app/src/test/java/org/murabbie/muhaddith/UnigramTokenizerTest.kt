package org.murabbie.muhaddith

import org.murabbie.muhaddith.semantic.UnigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** مطابقة مخرجات المقطّع الكوتليني لمخرجات HuggingFace على عيّنات عربية */
class UnigramTokenizerTest {
    private val tok by lazy { UnigramTokenizer.load(javaClass.getResourceAsStream("/e5_vocab.bin")!!) }

    private data class Fx(val text: String, val ids: List<Int>)

    private fun fixtures(): List<Fx> {
        val json = javaClass.getResourceAsStream("/tokenizer_fixtures.json")!!.bufferedReader().readText()
        val re = Regex("\\{\\s*\"text\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"ids\":\\s*\\[([^\\]]*)\\]\\s*\\}")
        return re.findAll(json).map { m ->
            val text = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            Fx(text, m.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull() })
        }.toList()
    }

    @Test fun matchesHuggingFaceOnFixtures() {
        val fx = fixtures()
        assert(fx.size >= 10) { "لم تُقرأ العيّنات" }
        var bad = 0
        for (f in fx) {
            val got = tok.encode(f.text, maxLen = 512).toList()
            if (got != f.ids) { bad++; println("MISMATCH «${f.text}»\n  want ${f.ids}\n  got  $got\n  toks ${tok.tokenize(f.text)}") }
        }
        assertEquals("عيّنات غير مطابقة", 0, bad)
    }

    @Test fun truncatesToMaxLen() {
        val ids = tok.encode("query: " + "الصلاة ".repeat(200), maxLen = 32)
        assertEquals(32, ids.size); assertEquals(0, ids.first()); assertEquals(2, ids.last())
    }
}

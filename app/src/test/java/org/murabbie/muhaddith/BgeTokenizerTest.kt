package org.murabbie.muhaddith

import org.murabbie.muhaddith.semantic.UnigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** المفردات المقلَّمة لنموذج BGE-M3 (٣١ ألف قطعة): تطابق مخرجات HuggingFace بعد إعادة الترقيم */
class BgeTokenizerTest {
    @Test fun prunedVocabMatchesRemappedHuggingFaceIds() {
        val tok = UnigramTokenizer.load(javaClass.getResourceAsStream("/bge_vocab.bin")!!)
        val json = javaClass.getResourceAsStream("/bge_fixtures.json")!!.bufferedReader().readText()
        val re = Regex("\\{\\s*\"text\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"ids\":\\s*\\[([^\\]]*)\\]")
        var n = 0; var bad = 0
        for (m in re.findAll(json)) {
            val text = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            val want = m.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull() }
            val got = tok.encode(text, maxLen = 512).toList()
            n++
            if (got != want) { bad++; println("MISMATCH «$text» want=$want got=$got toks=${tok.tokenize(text)}") }
        }
        assert(n >= 10)
        assertEquals(0, bad)
    }
}

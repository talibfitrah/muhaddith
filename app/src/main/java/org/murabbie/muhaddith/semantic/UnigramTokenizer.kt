package org.murabbie.muhaddith.semantic

import java.io.DataInputStream
import java.io.InputStream
import java.text.Normalizer

/**
 * مقطّع SentencePiece-Unigram (كما في XLM-R / multilingual-e5) مكتوب بكوتلن ليعمل على الهاتف بلا مكتبات.
 * يقرأ المفردات من ملف مدمج (MSBT) مُصدَّر من tokenizer.json، ويطابق مخرجات HuggingFace.
 */
class UnigramTokenizer(private val vocab: HashMap<String, Float>, private val ids: HashMap<String, Int>) {

    companion object {
        const val BOS = 0; const val PAD = 1; const val EOS = 2; const val UNK = 3
        private const val META = '▁'   // ▁

        fun load(input: InputStream): UnigramTokenizer {
            DataInputStream(input.buffered(1 shl 16)).use { d ->
                val magic = ByteArray(4); d.readFully(magic)
                require(String(magic) == "MSBT") { "ملف مفردات غير صالح" }
                val count = Integer.reverseBytes(d.readInt())
                val vocab = HashMap<String, Float>(count * 2); val ids = HashMap<String, Int>(count * 2)
                for (i in 0 until count) {
                    val len = java.lang.Short.reverseBytes(d.readShort()).toInt() and 0xFFFF
                    val b = ByteArray(len); d.readFully(b)
                    val piece = String(b, Charsets.UTF_8)
                    val score = java.lang.Float.intBitsToFloat(Integer.reverseBytes(d.readInt()))
                    vocab[piece] = score; ids[piece] = i
                }
                return UnigramTokenizer(vocab, ids)
            }
        }
    }

    private val minScore: Float = vocab.values.minOrNull() ?: -20f
    private val unkScore: Float = minScore - 10f
    private val maxPieceLen: Int = vocab.keys.maxOf { it.codePointCount(0, it.length) }.coerceAtMost(32)

    /** تطبيع كما في XLM-R: NFKC، ثم دمج المسافات المتتابعة */
    fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = s.replace(Regex(" {2,}"), " ")
        s = s.replace(' ', META)
        if (!s.startsWith(META)) s = META + s
        return s
    }

    /** يقسم إلى كلمات كلٌّ تبدأ بـ ▁ (Metaspace split) */
    private fun preTokenize(norm: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 1 until norm.length) {
            if (norm[i] == META) { out.add(norm.substring(start, i)); start = i }
        }
        if (start < norm.length) out.add(norm.substring(start))
        return out
    }

    /** أفضل تقطيع للكلمة (Viterbi) إلى قطع من المفردات */
    private fun viterbi(word: String): List<String> {
        val cps = word.codePoints().toArray()
        val n = cps.size
        if (n == 0) return emptyList()
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }; best[0] = 0f
        val back = IntArray(n + 1) { -1 }
        val unkAt = BooleanArray(n + 1)
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val sb = StringBuilder()
            var j = i
            while (j < n && j - i < maxPieceLen) {
                sb.appendCodePoint(cps[j]); j++
                val sc = vocab[sb.toString()]
                if (sc != null) {
                    val cand = best[i] + sc
                    if (cand > best[j]) { best[j] = cand; back[j] = i; unkAt[j] = false }
                }
            }
            // حرف واحد غير معروف
            val cand = best[i] + unkScore
            if (cand > best[i + 1]) { best[i + 1] = cand; back[i + 1] = i; unkAt[i + 1] = true }
        }
        val pieces = ArrayList<String>()
        var k = n
        while (k > 0) {
            val s = back[k]
            pieces.add(if (unkAt[k]) "<unk>" else String(cps, s, k - s))
            k = s
        }
        pieces.reverse()
        return pieces
    }

    fun tokenize(text: String): List<String> =
        if (text.isEmpty()) emptyList() else preTokenize(normalize(text)).flatMap { viterbi(it) }

    /** المعرّفات مع <s> … </s>، مقصوصة إلى maxLen */
    fun encode(text: String, maxLen: Int = 128): IntArray {
        val toks = tokenize(text).map { ids[it] ?: UNK }.take(maxLen - 2)
        return intArrayOf(BOS) + toks.toIntArray() + intArrayOf(EOS)
    }
}

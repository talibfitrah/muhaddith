package org.murabbie.muhaddith.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * البحث الدلالي على الهاتف: يحوّل الاستعلام إلى متجه بنموذج multilingual-e5-small (ONNX int8)
 * ثم يقارنه بمتجهات المتون المحسوبة مسبقًا (VectorIndex).
 */
class SemanticEngine(private val dir: File) {

    companion object {
        const val MODEL_FILE = "model.onnx"
        const val VOCAB_FILE = "vocab.bin"
        const val CONFIG_FILE = "model.json"
        fun vectorsFile(dir: File, dataset: String) = File(dir, "vectors_$dataset.bin")
        fun modelInstalled(dir: File) = File(dir, MODEL_FILE).length() > 1_000_000 && File(dir, VOCAB_FILE).length() > 1000
    }

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: UnigramTokenizer? = null
    @Volatile private var index: VectorIndex? = null
    @Volatile private var indexDataset: String? = null
    @Volatile var lastError: String? = null
    @Volatile var lastQueryMs: Long = 0
    @Volatile var lastSearchMs: Long = 0

    /** إعدادات النموذج من model.json: اسم النموذج، سابقة الاستعلام، أقصى طول، البعد */
    data class ModelConfig(val name: String, val queryPrefix: String, val maxLen: Int, val dim: Int)
    val config: ModelConfig get() {
        val f = File(dir, CONFIG_FILE)
        if (!f.exists()) return ModelConfig("?", "", 64, 1024)
        return try {
            val o = org.json.JSONObject(f.readText())
            ModelConfig(o.optString("name", "?"), o.optString("query_prefix", ""), o.optInt("max_len", 64), o.optInt("dim", 1024))
        } catch (_: Exception) { ModelConfig("?", "", 64, 1024) }
    }

    val modelReady get() = modelInstalled(dir)

    fun vectorsReady(dataset: String) = vectorsFile(dir, dataset).length() > 1000

    fun ready(dataset: String) = modelReady && vectorsReady(dataset)

    @Synchronized
    private fun ensureModel() {
        if (session != null && tokenizer != null) return
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // تقليل الذاكرة: بلا حجز مسبق للأنماط
            setMemoryPatternOptimization(false)
        }
        session = env.createSession(File(dir, MODEL_FILE).absolutePath, opts)
        tokenizer = UnigramTokenizer.load(File(dir, VOCAB_FILE).inputStream())
    }

    @Synchronized
    private fun ensureIndex(dataset: String): VectorIndex {
        val cur = index
        if (cur != null && indexDataset == dataset) return cur
        val idx = VectorIndex.open(vectorsFile(dir, dataset))
        index = idx; indexDataset = dataset
        return idx
    }

    /** يحرّر الذاكرة (مثلًا بعد تبديل الحزمة) */
    @Synchronized
    fun release() {
        runCatching { session?.close() }; session = null; tokenizer = null; index = null; indexDataset = null
    }

    /** متجه الاستعلام (معيّر) */
    fun embedQuery(text: String): FloatArray {
        ensureModel()
        val tok = tokenizer!!; val sess = session!!
        val cfg = config
        val ids = tok.encode(cfg.queryPrefix + text.trim(), maxLen = cfg.maxLen)
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, ids.size.toLong())
        val idBuf = LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() })
        val maskBuf = LongBuffer.wrap(LongArray(ids.size) { 1L })
        OnnxTensor.createTensor(env, idBuf, shape).use { t1 ->
            OnnxTensor.createTensor(env, maskBuf, shape).use { t2 ->
                sess.run(mapOf("input_ids" to t1, "attention_mask" to t2)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val arr = out[0].value as Array<FloatArray>
                    return arr[0]
                }
            }
        }
    }

    /** أقرب المتون معنًى إلى الاستعلام: معرّفات مرتّبة تنازليًّا بالتشابه */
    fun search(query: String, dataset: String, k: Int = 400): List<VectorIndex.Hit> {
        return try {
            val t0 = System.currentTimeMillis()
            val q = embedQuery(query)
            lastQueryMs = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            val hits = ensureIndex(dataset).search(q, k)
            lastSearchMs = System.currentTimeMillis() - t1
            hits
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString(); emptyList()
        }
    }
}

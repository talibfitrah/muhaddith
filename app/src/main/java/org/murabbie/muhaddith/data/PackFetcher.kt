package org.murabbie.muhaddith.data

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * تنزيل ملفات حزمة (ملف واحد أو أجزاء) إلى مجلد، مع الاستئناف بـ HTTP Range والتحقق بـ sha256.
 * خالٍ من أي اعتماد على أندرويد ليمكن اختباره على JVM.
 *
 * onProgress(bytesDone, total, phase): phase = 0 تنزيل، 1 تحقّق.
 */
class PackFetcher(
    private val dir: File,
    private val baseUrl: String,
    /** يُستدعى أثناء النقل؛ إن أعاد رسالة غير فارغة توقّف التنزيل بها (إلغاء، أو شرط الواي فاي) */
    private val gate: () -> String? = { null }
) {
    class Stopped(msg: String) : IllegalStateException(msg)

    private val source = PackSource(baseUrl)

    companion object {
        const val PART_SUFFIX = ".gz.part"
        const val SIZE_SUFFIX = ".size"
    }

    fun partFile(name: String) = File(dir, name.substringAfterLast('/') + PART_SUFFIX)

    /** ينزّل كل الملفات ويتحقّق ويعيد قائمتها بالترتيب (جاهزة للقراءة متتابعةً) */
    fun fetch(pack: RemotePack, onProgress: (Long, Long, Int) -> Unit): List<File> {
        val names = if (pack.parts.isEmpty()) listOf(pack.file) else pack.parts
        val files = names.map { partFile(it) }
        val total = pack.sizeGz
        var offset = 0L
        for ((i, name) in names.withIndex()) {
            val part = files[i]
            if (isComplete(part)) {
                offset += part.length()
                onProgress(offset, total, 0)
            } else {
                offset += fetchOne(name, part, total, offset, onProgress)
            }
        }
        val got = files.sumOf { it.length() }
        if (total > 0 && got != total) {
            throw IllegalStateException("انقطع التنزيل عند ${got / 1_000_000} م.ب — أعد المحاولة ليُستأنف")
        }
        pack.sha256?.let { want ->
            val sum = sha256Of(files) { d, t -> onProgress(d, t, 1) }
            if (!sum.equals(want, ignoreCase = true)) {
                files.forEach { discard(it) }
                throw IllegalStateException("الملف المنزَّل تالف (اختلاف sha256) — حُذف، أعد التنزيل")
            }
        }
        return files
    }

    fun discard(part: File) { part.delete(); File(part.path + SIZE_SUFFIX).delete() }

    fun discardAll() {
        dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) || it.name.endsWith(PART_SUFFIX + SIZE_SUFFIX) }?.forEach { it.delete() }
    }

    fun partialBytes(): Long = dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.sumOf { it.length() } ?: 0L

    private fun isComplete(part: File): Boolean {
        if (!part.exists() || part.length() == 0L) return false
        val expected = File(part.path + SIZE_SUFFIX).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return false
        return part.length() == expected
    }

    /** ينزّل ملفًا واحدًا مستأنفًا من طوله الحالي، ويعيد طوله النهائي */
    private fun fetchOne(name: String, part: File, grandTotal: Long, offset: Long, onProgress: (Long, Long, Int) -> Unit): Long {
        var have = if (part.exists()) part.length() else 0L
        val conn = source.connect(name, binary = true, rangeFrom = have)
        try {
            val code = conn.responseCode
            if (code == 416) { markComplete(part); return part.length() }
            val resuming = code == 206
            if (!resuming) have = 0
            val len = conn.contentLengthLong
            val thisTotal = if (len < 0) -1L else if (resuming) len + have else len
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(have); raf.seek(have)
                val buf = ByteArray(1 shl 16)
                var done = have
                var last = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n); done += n
                        if (done - last > (1L shl 20)) {
                            last = done; onProgress(offset + done, if (grandTotal > 0) grandTotal else thisTotal, 0)
                            gate()?.let { throw Stopped(it) }
                        }
                    }
                }
                onProgress(offset + done, if (grandTotal > 0) grandTotal else thisTotal, 0)
            }
            if (thisTotal > 0 && part.length() != thisTotal) {
                throw IllegalStateException("انقطع التنزيل عند ${(offset + part.length()) / 1_000_000} م.ب — أعد المحاولة ليُستأنف")
            }
            markComplete(part)
            return part.length()
        } finally { conn.disconnect() }
    }

    private fun markComplete(part: File) { File(part.path + SIZE_SUFFIX).writeText(part.length().toString()) }

    private fun sha256Of(files: List<File>, onProgress: (Long, Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = files.sumOf { it.length() }
        var done = 0L
        var last = 0L
        val buf = ByteArray(1 shl 16)
        for (f in files) FileInputStream(f).use { input ->
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n); done += n
                if (done - last > (8L shl 20)) { last = done; onProgress(done, total) }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

package org.murabbie.muhaddith.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** حفظ المستند المصدَّر ومشاركته */
object ExportFiles {
    fun mime(ext: String) = if (ext == "pdf") "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** يكتب الملف في مجلد التصدير الداخلي ويعيد مساره */
    fun writeCache(ctx: Context, name: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        // تنظيف القديم
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3L * 24 * 3600 * 1000 }?.forEach { it.delete() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    fun shareIntent(ctx: Context, f: File, ext: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime(ext); putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openIntent(ctx: Context, f: File, ext: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        return Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime(ext)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** نسخة في مجلد التنزيلات العام (Download/Muhaddith) ليجدها المستخدم في مدير الملفات */
    fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray, ext: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(ext))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Muhaddith")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("تعذّر إنشاء الملف في التنزيلات")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("تعذّر الكتابة")
            return "Download/Muhaddith/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Muhaddith").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            return "Download/Muhaddith/$name"
        }
    }
}

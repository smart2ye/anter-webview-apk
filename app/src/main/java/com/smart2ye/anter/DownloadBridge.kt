package com.smart2ye.anter

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File

/**
 * جسر تنزيل بين JavaScript في WebView وبين التخزين المحلي.
 * يُتاح في الصفحة ككائن: window.AnterDownload
 */
class DownloadBridge(private val context: Context) {

    companion object {
        private const val SUBFOLDER = "ANTER"
    }

    @JavascriptInterface
    fun isAvailable(): Boolean = true

    /**
     * يحفظ صورة base64 في Download/ANTER/.
     * يعيد true عند النجاح، false عند الفشل.
     */
    @JavascriptInterface
    fun saveImage(base64Data: String, filename: String): Boolean {
        if (base64Data.isBlank() || filename.isBlank()) {
            notify("اسم الملف أو البيانات فارغة")
            return false
        }
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val mime = guessMime(safeName)

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bytes, safeName, mime)
            } else {
                saveViaFile(bytes, safeName)
            }
            if (ok) notify("تم حفظ الصورة في مجلد ANTER")
            ok
        } catch (e: Exception) {
            notify("تعذّر حفظ الصورة")
            false
        }
    }

    /** Android 10+ — عبر MediaStore.Downloads (لا يحتاج إذن). */
    private fun saveViaMediaStore(bytes: ByteArray, name: String, mime: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    /** API ≤ 28 — كتابة مباشرة إلى Download/ANTER/. */
    private fun saveViaFile(bytes: ByteArray, name: String): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBFOLDER
            )
            if (!dir.exists()) dir.mkdirs()
            var target = File(dir, name)
            if (target.exists()) {
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                var i = 1
                while (target.exists() && i < 1000) {
                    target = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
                    i++
                }
            }
            target.writeBytes(bytes)
            MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), null, null
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "jpeg", "jpg" -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun notify(msg: String) {
        try {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}

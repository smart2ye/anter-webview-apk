package com.smart2ye.anter

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * جسر مشاركة بين JavaScript في WebView وبين Intent.ACTION_SEND.
 * يُتاح في الصفحة ككائن: window.AnterShare
 *
 * الاستخدام من JS:
 *   if (window.AnterShare && window.AnterShare.isAvailable()) {
 *       window.AnterShare.share(title, text, url);
 *   }
 */
class ShareBridge(private val activity: Activity) {

    @JavascriptInterface
    fun isAvailable(): Boolean = true

    @JavascriptInterface
    fun share(title: String?, text: String?, url: String?) {
        try {
            val safeTitle = title?.trim().orEmpty()
            val safeText = text?.trim().orEmpty()
            val safeUrl = url?.trim().orEmpty()

            // نجمع النص + الرابط في body (بعض التطبيقات تتجاهل EXTRA_TEXT إن كان الرابط وحده)
            val body = buildString {
                if (safeText.isNotEmpty()) append(safeText)
                if (safeUrl.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(safeUrl)
                }
            }.trim()

            if (body.isEmpty()) return

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                if (safeTitle.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, safeTitle)
            }

            val chooser = Intent.createChooser(sendIntent, safeTitle.ifEmpty { "مشاركة" })
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(chooser)
        } catch (e: Exception) {
            try {
                Toast.makeText(activity, "تعذر فتح نافذة المشاركة", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                // تجاهل
            }
        }
    }
}

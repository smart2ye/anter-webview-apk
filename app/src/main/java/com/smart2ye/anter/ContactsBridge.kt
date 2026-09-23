package com.smart2ye.anter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.security.MessageDigest

/**
 * جسر بين JavaScript في WebView وبين Kotlin.
 * يُتاح في الصفحة ككائن: window.AnterContacts
 */
class ContactsBridge(
    private val activity: Activity,
    private val webView: WebView
) {

    @JavascriptInterface
    fun requestHashes() {
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_CONTACTS),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        readAndSendHashes()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            readAndSendHashes()
        } else {
            sendToJs("[]")
        }
    }

    private fun readAndSendHashes() {
        Thread {
            val hashes = try {
                readContactHashes()
            } catch (_: Exception) {
                emptyList()
            }
            val payload = JSONArray(hashes).toString()
            Handler(Looper.getMainLooper()).post {
                sendToJs(payload)
            }
        }.start()
    }

    private fun sendToJs(jsonArray: String) {
        val js = "window.onAnterHashesReceived($jsonArray);"
        webView.evaluateJavascript(js, null)
    }

    private fun readContactHashes(): List<String> {
        val result = LinkedHashSet<String>()

        val cursor: Cursor? = activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null
        )

        cursor?.use {
            val idx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idx < 0) return emptyList()

            while (it.moveToNext() && result.size < MAX_CONTACTS) {
                val raw = it.getString(idx) ?: continue
                val variants = normalizeVariants(raw)
                for (v in variants) {
                    val h = sha256Hex(v)
                    if (h != null) result.add(h)
                }
            }
        }

        return result.toList()
    }

    /**
     * يُنتج صيغًا محتملة للرقم لتزيد فرصة المطابقة.
     * مثال: "052800941" → ["+96652800941", "+96752800941", ...]
     * نحن نجرب أكثر من كود دولة لأن التطبيق قد يقرأ أرقامًا من دول مختلفة.
     */
    private fun normalizeVariants(raw: String): List<String> {
        val digits = raw.replace(Regex("[^0-9+]"), "")
        if (digits.isEmpty()) return emptyList()

        val variants = LinkedHashSet<String>()

        // 1) إن كان فيه + بالفعل
        if (digits.startsWith("+")) {
            val cleaned = stripLeadingZero(digits)
            if (isValid(cleaned)) variants.add(cleaned)
            return variants.toList()
        }

        // 2) يبدأ بـ 00 (صيغة دولية بديلة)
        if (digits.startsWith("00") && digits.length > 4) {
            val cleaned = stripLeadingZero("+" + digits.substring(2))
            if (isValid(cleaned)) variants.add(cleaned)
            return variants.toList()
        }

        // 3) يبدأ بـ 0 (رقم محلي)
        if (digits.startsWith("0")) {
            val rest = digits.trimStart('0')
            // جرّب أكواد دولة شائعة
            for (cc in COMMON_COUNTRY_CODES) {
                val candidate = "+" + cc + rest
                if (isValid(candidate)) variants.add(candidate)
            }
            return variants.toList()
        }

        // 4) رقم بلا + وبلا 0 — قد يكون أصلاً بصيغة دولية بلا +
        // نجرّب أكثر من دولة أيضًا
        for (cc in COMMON_COUNTRY_CODES) {
            if (digits.startsWith(cc)) {
                val candidate = "+" + digits
                if (isValid(candidate)) variants.add(candidate)
            }
        }

        return variants.toList()
    }

    private fun stripLeadingZero(s: String): String {
        // إزالة صفر زائد بعد كود الدولة: +9660xxx → +966xxx
        if (s.length > 4 && s[1].isDigit() && s[2].isDigit() && s[3].isDigit() && s.getOrNull(4) == '0') {
            return s.substring(0, 4) + s.substring(5)
        }
        return s
    }

    private fun isValid(s: String): Boolean {
        if (!s.startsWith("+")) return false
        val d = s.substring(1)
        return d.all { it.isDigit() } && d.length in 8..15
    }

    private fun sha256Hex(input: String): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 7101
        private const val MAX_CONTACTS = 500

        // أكواد الدولة التي نجرّبها لكل رقم محلي
        val COMMON_COUNTRY_CODES = listOf(
            "967", // اليمن
            "966", // السعودية
            "971", // الإمارات
            "968", // عمان
            "973", // البحرين
            "974", // قطر
            "965", // الكويت
            "962", // الأردن
            "961", // لبنان
            "20",  // مصر
            "964", // العراق
            "963", // سوريا
            "218", // ليبيا
            "216", // تونس
            "213", // الجزائر
            "212", // المغرب
            "249", // السودان
        )
    }
}

package com.smart2ye.anter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * خدمة خلفية تفحص إشعارات ANTER الجديدة كل 15 ثانية وتُظهر إشعارًا في النظام.
 * تعمل في المقدمة (Foreground) لتبقى نشطة حتى مع إغلاق التطبيق.
 */
class NotificationService : Service() {

    private var running = false
    private var lastCount = -1
    private val baseUrl = "https://anter-1.onrender.com"

    companion object {
        private const val CHANNEL_FG = "anter_fg"
        private const val CHANNEL_NOTIF = "anter_notif"
        private const val FG_ID = 1001
        private const val NOTIF_ID = 2001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fgNotif = NotificationCompat.Builder(this, CHANNEL_FG)
            .setContentTitle("ANTER")
            .setContentText("جاهز لتلقي الإشعارات")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(FG_ID, fgNotif)

        if (!running) {
            running = true
            thread(name = "anter-poll") {
                while (running) {
                    try { poll() } catch (_: Exception) {}
                    Thread.sleep(15_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun poll() {
        val body = httpGet("$baseUrl/notifications/unread_count") ?: return
        val c = Regex("\"count\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (lastCount >= 0 && c > lastCount) {
            val latest = httpGet("$baseUrl/notifications/latest")
            var actor = "إشعار جديد"
            var text = "لديك إشعار جديد"
            if (latest != null) {
                try {
                    val obj = org.json.JSONObject(latest)
                    actor = obj.optString("actor_name", "إشعار جديد")
                    text  = obj.optString("text", "لديك إشعار جديد")
                } catch (_: Exception) {
                    // في حال فشل التحليل، نُبقي النص الافتراضي
                }
            }
            showNotification(actor, text)
        }
        lastCount = c
    }

    private fun httpGet(url: String): String? {
        return try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: ""
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ANTERApp/1.0.11")
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            body
        } catch (_: Exception) { null }
    }

    private fun showNotification(title: String, text: String) {
        // يفتح التطبيق عند الضغط على الإشعار
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.parse("https://anter-1.onrender.com/notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_NOTIF)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FG, "خدمة ANTER", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NOTIF, "إشعارات ANTER", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

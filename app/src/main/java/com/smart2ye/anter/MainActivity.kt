package com.smart2ye.anter

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var contactsBridge: ContactsBridge

    companion object {
        private const val DEFAULT_START_URL = "https://anter-1.onrender.com/"
        private const val PREFS_NAME = "anter_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val APP_VERSION = "1.0.8"
    }

    private fun getSavedStartUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val url = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty()
        return if (url.isNotEmpty()) url else DEFAULT_START_URL
    }

    private fun saveStartUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normalized)
            .apply()
    }

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { !it }) {
                Toast.makeText(this, "بعض الصلاحيات مرفوضة — قد لا تعمل بعض الميزات.", Toast.LENGTH_SHORT).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        // نسخة واحدة من الجسر (كان الخطأ: نسختان)
        contactsBridge = ContactsBridge(this, webView)
        webView.addJavascriptInterface(contactsBridge, "AnterContacts")

        requestBasePermissions()
        configureWebView()

        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setColorSchemeColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.anter_primary),
            androidx.core.content.ContextCompat.getColor(this, R.color.anter_cyan)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(0xFFFFFFFF.toInt())
        swipeRefresh.setDistanceToTriggerSync(280)
        swipeRefresh.setOnRefreshListener {
            webView.reload()
            webView.postDelayed({ swipeRefresh.isRefreshing = false }, 5000)
        }

        findViewById<android.widget.ImageButton>(R.id.btnServerSettings).setOnClickListener {
            showServerDialog()
        }
        findViewById<android.widget.ImageButton>(R.id.btnServerSettings).setOnLongClickListener {
            saveStartUrl(DEFAULT_START_URL)
            Toast.makeText(this, "تمت العودة إلى الخادم الافتراضي.", Toast.LENGTH_SHORT).show()
            webView.loadUrl(DEFAULT_START_URL)
            true
        }

        webView.loadUrl(getSavedStartUrl())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // تأكيد الخروج بدل الخروج الفوري
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("خروج")
                        .setMessage("هل تريد إغلاق ANTER؟")
                        .setPositiveButton("خروج") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
        })
    }

    private fun showServerDialog() {
        val current = getSavedStartUrl()
        val input = EditText(this).apply {
            hint = "https://anter-1.onrender.com/"
            setText(current)
            setSelection(text.length)
        }

        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("عنوان الخادم")
            .setMessage("اتركه فارغًا للعودة إلى الخادم الافتراضي.")
            .setView(container)
            .setPositiveButton("حفظ") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    saveStartUrl(DEFAULT_START_URL)
                    Toast.makeText(this, "تمت العودة إلى الخادم الافتراضي.", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(DEFAULT_START_URL)
                } else {
                    val normalized = if (value.endsWith("/")) value else "$value/"
                    saveStartUrl(normalized)
                    Toast.makeText(this, "جارٍ الاتصال بـ $normalized", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(normalized)
                }
            }
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("افتراضي") { _, _ ->
                saveStartUrl(DEFAULT_START_URL)
                Toast.makeText(this, "تمت العودة إلى الافتراضي.", Toast.LENGTH_SHORT).show()
                webView.loadUrl(DEFAULT_START_URL)
            }
            .show()
    }

    private fun requestBasePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        needed += Manifest.permission.CAMERA
        needed += Manifest.permission.RECORD_AUDIO
        needed += Manifest.permission.MODIFY_AUDIO_SETTINGS
        permissionLauncher.launch(needed.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " ANTERApp/$APP_VERSION"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.isScrollbarFadingEnabled = true
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: ""
                val host = url.host ?: return false

                // schemes خاصة — افتحها في التطبيق المناسب
                if (scheme == "mailto" || scheme == "tel" || scheme == "sms" ||
                    scheme == "whatsapp" || scheme == "intent" ||
                    scheme == "tg" || scheme == "market") {
                    return openExternal(url)
                }

                val savedHost = try {
                    Uri.parse(getSavedStartUrl()).host
                } catch (_: Exception) { null }

                val allowed = host.endsWith("onrender.com") ||
                              (savedHost != null && host == savedHost) ||
                              host == "localhost" ||
                              host == "127.0.0.1" ||
                              host == "10.0.2.2"

                return if (allowed) {
                    false
                } else {
                    openExternal(url)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر الاتصال. تحقق من الشبكة.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // نرفض geolocation دائمًا (لا نحتاجه في ANTER)
                callback.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // تنزيل APK من صفحة الهبوط
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setDescription("جارٍ التنزيل...")
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(filename)
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "بدأ التنزيل: $filename", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // إن فشل التنزيل الداخلي، افتح المتصفح
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
    }

    private fun openExternal(url: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "لا يوجد تطبيق يفتح هذا الرابط.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ContactsBridge.PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                          grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            // نستخدم نفس النسخة (كان الخطأ: نسخة جديدة)
            contactsBridge.onPermissionResult(granted)
        }
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() { super.onDestroy(); webView.destroy() }
}

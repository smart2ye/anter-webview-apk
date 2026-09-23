package com.smart2ye.anter

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val START_URL = "https://anter-1.onrender.com/"
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
            // WebView يتعامل مع النتائج عبر onPermissionRequest إذا سبق منحها
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

        requestBasePermissions()
        configureWebView()

        // جسر قراءة جهات الاتصال (متاح في JS: window.AnterContacts)
        webView.addJavascriptInterface(
            ContactsBridge(this, webView),
            "AnterContacts"
        )

        // Pull-to-refresh يعمل فقط عندما يكون WebView في أعلى الصفحة
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setColorSchemeColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.anter_primary),
            androidx.core.content.ContextCompat.getColor(this, R.color.anter_primary_dark)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(0xFFFFFFFF.toInt())
        swipeRefresh.setDistanceToTriggerSync(280)
        swipeRefresh.setOnRefreshListener {
            webView.reload()
            // احتياط: إن لم يُستدعَ onPageFinished، أنهِ المؤشر خلال 5 ثوانٍ
            webView.postDelayed({ swipeRefresh.isRefreshing = false }, 5000)
        }

        webView.loadUrl(START_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun requestBasePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += android.Manifest.permission.POST_NOTIFICATIONS
        }
        needed += android.Manifest.permission.CAMERA
        needed += android.Manifest.permission.RECORD_AUDIO
        needed += android.Manifest.permission.MODIFY_AUDIO_SETTINGS
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
        settings.userAgentString = settings.userAgentString + " ANTERApp/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.isScrollbarFadingEnabled = true
        // Over-scroll معطّل بصريًا، لكن نُبقي كشف السكرول ليعمل Pull-to-refresh
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val host = url.host ?: return false
                return if (host.endsWith("onrender.com")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {}
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
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
            // نُبلّغ الجسر بالنتيجة (نستخدمه للبحث)
            val bridge = ContactsBridge(this, webView)
            bridge.onPermissionResult(granted)
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}

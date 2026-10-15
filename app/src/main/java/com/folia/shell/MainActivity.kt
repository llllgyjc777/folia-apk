package com.folia.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Folia 安卓套壳客户端
 * 用系统 WebView 加载 Folia 在线音乐网页，提供接近原生的体验。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        } else {
            callback.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swiperefresh)
        progressBar = findViewById(R.id.progress_bar)

        setupWebView()
        setupBackHandling()

        webView.loadUrl(HOME_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // 媒体播放（音乐播放器关键项）
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Cookie（登录/同步需要）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 去掉 WebView 标识中的 "; wv"，让部分站点按普通移动浏览器处理
        settings.userAgentString = settings.userAgentString?.replace("; wv", "")
            ?: settings.userAgentString

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        // 忽略无法处理的协议
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    showOfflineHint()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback1: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePathCallback1
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // 下载交给系统 DownloadManager
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                ensureStoragePermission()
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("下载中…")
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(fileName)
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "已开始下载：$fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        })

        // 下拉刷新（仅顶部启用）
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setColorSchemeColors(0xFF09090B.toInt())
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun showOfflineHint() {
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{height:100%;margin:0;background:#09090b;color:#e5e5e5;
                font-family:system-ui,-apple-system,sans-serif;
                display:flex;align-items:center;justify-content:center;text-align:center}
              .box{padding:24px}
              h1{font-size:22px;font-weight:600;margin:0 0 10px}
              p{font-size:14px;color:#a3a3a3;line-height:1.7;margin:0 0 22px}
              button{background:#fff;color:#09090b;border:0;border-radius:999px;
                padding:12px 30px;font-size:15px;font-weight:600}
            </style></head>
            <body><div class="box">
              <h1>无法连接到 Folia</h1>
              <p>网络似乎不可用。<br>请检查网络连接后重试。</p>
              <button onclick="location.href='$HOME_URL'">重新加载</button>
            </div></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(HOME_URL, html, "text/html", "utf-8", null)
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // 保持后台播放：不调用 pauseTimers
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Folia Web 站点地址（可替换为你自己的部署地址）
        private const val HOME_URL = "https://folia.lanerc.me"
        private const val REQ_STORAGE = 1001
    }
}
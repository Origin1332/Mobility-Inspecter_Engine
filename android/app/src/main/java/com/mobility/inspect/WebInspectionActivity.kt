package com.mobility.engine

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** All executable content is packaged in the APK. No INTERNET permission is needed. */
class WebInspectionActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var picker: ValueCallback<Array<Uri>>? = null
    private var pendingJson: String? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picker?.onReceiveValue(uri?.let { arrayOf(it) }); picker = null
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingJson; pendingJson = null
        if (uri != null && text != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("출력 파일을 열 수 없습니다.")
                Toast.makeText(this, "검사 기록 저장 완료", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.PathHandler { path ->
            if (!path.startsWith("web/") || path.split('/').any { it == ".." }) return@PathHandler blocked()
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "js", "mjs" -> "text/javascript"
                "wasm" -> "application/wasm"
                "json", "webmanifest" -> "application/json"
                "html" -> "text/html"
                "css" -> "text/css"
                "svg" -> "image/svg+xml"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            try { WebResourceResponse(mime, null, assets.open(path)) }
            catch (_: Exception) { WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))) }
        }).addPathHandler("/capture/", WebViewAssetLoader.PathHandler { path ->
            if (!intent.getBooleanExtra("capture",false) || path !in setOf("current.jpg","current.json")) return@PathHandler blocked()
            try { WebResourceResponse(if(path.endsWith("jpg")) "image/jpeg" else "application/json",null,
                java.io.File(cacheDir,"inspection-capture/$path").inputStream()) }
            catch (_: Exception) { blocked() }
        }).build()
        fun response(url: Uri): WebResourceResponse =
            if (url.scheme == "https" && url.host == WebViewAssetLoader.DEFAULT_DOMAIN)
                loader.shouldInterceptRequest(url) ?: blocked() else blocked()
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false
            // Selected content:// files are supplied explicitly by Android's document picker.
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = response(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN ||
                    !request.url.path.orEmpty().startsWith("/assets/web/")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = response(request.url)
            })
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@WebInspectionActivity).setMessage(message)
                    .setPositiveButton("계속") { _,_ -> result.confirm() }
                    .setNegativeButton("취소") { _,_ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                picker?.onReceiveValue(null); picker = callback
                val image = params.acceptTypes.any { it.startsWith("image/") }
                openFile.launch(if (image) arrayOf("image/*") else arrayOf("application/zip", "application/octet-stream"))
                return true
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun saveJson(text: String) {
                if (text.length > 2 * 1024 * 1024) {
                    runOnUiThread { Toast.makeText(this@WebInspectionActivity,"JSON 출력은 2MiB 이하만 지원합니다. 기기 내부 검사 저장을 사용하세요.",Toast.LENGTH_LONG).show() };return
                }
                try { org.json.JSONObject(text) } catch (_: Exception) { return }
                runOnUiThread {
                    if (pendingJson == null && !isDestroyed) {
                        pendingJson = text; saveFile.launch("engine-inspection.json")
                    }
                }
            }
        }, "AndroidExport")
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("Boolean(window.inspectionDirty && window.inspectionDirty())") { result ->
                    if(result == "true") android.app.AlertDialog.Builder(this@WebInspectionActivity)
                        .setMessage("저장하지 않은 변경 사항을 버리고 나갈까요?")
                        .setPositiveButton("나가기") { _,_ -> finish() }.setNegativeButton("계속 검사",null).show()
                    else finish()
                }
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html" + if(intent.getBooleanExtra("capture",false)) "?capture=1" else "")
    }
    override fun onDestroy() {
        picker?.onReceiveValue(null); picker = null
        if (::web.isInitialized) { web.removeJavascriptInterface("AndroidExport"); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}

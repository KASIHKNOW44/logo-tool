package com.Logo.tool.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.Logo.tool.bridge.DownloadBridge
import com.Logo.tool.bridge.RootBridge
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorWebViewScreen(
    isVisible: Boolean,
    isDarkTheme: Boolean,
    isRootChecker: () -> Boolean,
    onSavedFile: (savedPath: String, cacheCopy: File, fileName: String) -> Unit,
    onRequestDumpToEditor: () -> Unit,
    onReceiveEditedLogoForFlash: (file: File) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@rememberLauncherForActivityResult

        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    BackHandler(enabled = isVisible && webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            webViewRef?.destroy()
        }
    }

    val rootJs = remember {
        context.assets.open("root.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                addJavascriptInterface(
                    DownloadBridge(ctx.applicationContext, onSavedFile),
                    "Android"
                )
                addJavascriptInterface(
                    RootBridge(
                        context = ctx.applicationContext,
                        isRootChecker = isRootChecker,
                        onRequestDumpToEditor = onRequestDumpToEditor,
                        onReceiveEditedLogoForFlash = onReceiveEditedLogoForFlash
                    ),
                    "AndroidRoot"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return !url.startsWith("file:///android_asset/")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(rootJs, null)
                        view?.evaluateJavascript(
                            "window.__ltSyncTheme && window.__ltSyncTheme($isDarkTheme);",
                            null
                        )
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = filePathCallback

                        val acceptTypes = fileChooserParams?.acceptTypes
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()

                        val mimeType = when {
                            acceptTypes.any { it.startsWith("image/") } -> "image/*"
                            acceptTypes.any { it.startsWith("video/") } -> "video/*"
                            else -> "*/*"
                        }

                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = mimeType
                            if (acceptTypes.any { it.contains("zip") }) {
                                putExtra(
                                    Intent.EXTRA_MIME_TYPES,
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                )
                            }
                        }
                        return try {
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (_: Throwable) {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = null
                            false
                        }
                    }
                }

                loadUrl("file:///android_asset/logotool.html")
                webViewRef = this
                onWebViewReady(this)
            }
        },
        update = { view ->
            view.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    )
}

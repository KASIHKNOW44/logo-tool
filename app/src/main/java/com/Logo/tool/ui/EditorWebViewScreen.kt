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
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.Logo.tool.BuildConfig
import com.Logo.tool.bridge.RootBridge
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorWebViewScreen(
    isVisible: Boolean,
    isDarkTheme: Boolean,
    language: String,
    isRootChecker: () -> Boolean,
    onSetThemeFromHtml: (String) -> Unit,
    onSetLangFromHtml: (String) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onRequestDumpToEditor: () -> Unit,
    onReceiveEditedLogoForFlash: (File) -> Unit,
    onReceiveFileForSafSave: (File, String, String) -> Unit,
    onRequestNativeBootAnim: (String) -> String,
    onVideoPickedUri: (Uri?) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var lastChooserWasVideo by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val latestDark by rememberUpdatedState(isDarkTheme)
    val latestLang by rememberUpdatedState(language)

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
        val firstUri = uris?.firstOrNull()
        if (firstUri != null) {
            val mime = context.contentResolver.getType(firstUri).orEmpty()
            val name = com.Logo.tool.util.FileUtils.getDisplayName(context, firstUri).lowercase()
            val looksVideo = lastChooserWasVideo ||
                mime.startsWith("video/") ||
                name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".3gp")
            if (looksVideo) {
                onVideoPickedUri(firstUri)
            }
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

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                addJavascriptInterface(
                    RootBridge(
                        context = ctx.applicationContext,
                        isRootChecker = isRootChecker,
                        onSetTheme = onSetThemeFromHtml,
                        onSetLang = onSetLangFromHtml,
                        onSetKeepScreenOn = onSetKeepScreenOn,
                        onRequestDumpToEditor = onRequestDumpToEditor,
                        onReceiveEditedLogoForFlash = onReceiveEditedLogoForFlash,
                        onReceiveFileForSafSave = onReceiveFileForSafSave,
                        onRequestNativeBootAnim = onRequestNativeBootAnim
                    ),
                    "AndroidRoot"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        return assetLoader.shouldInterceptRequest(url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return !url.startsWith("https://appassets.androidplatform.net/")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val modeStr = if (latestDark) "dark" else "light"
                        val langStr = if (latestLang == "en") "en" else "id"
                        view?.evaluateJavascript(rootJs, null)
                        view?.evaluateJavascript(
                            "window.applyTheme && window.applyTheme('$modeStr');" +
                                "window.setLang && window.setLang('$langStr');",
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

                        val isVideoChooser = acceptTypes.any { it.startsWith("video/") }
                        lastChooserWasVideo = isVideoChooser
                        val mimeType = when {
                            acceptTypes.any { it.startsWith("image/") } -> "image/*"
                            isVideoChooser -> "video/*"
                            else -> "*/*"
                        }

                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = mimeType
                            if (acceptTypes.any { it.contains("zip") }) {
                                putExtra(
                                    Intent.EXTRA_MIME_TYPES,
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
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

                loadUrl("https://appassets.androidplatform.net/assets/logotool.html")
                webViewRef = this
                onWebViewReady(this)
            }
        },
        update = { view ->
            // Keep WebView visible behind opaque RootToolScreen so Chromium never throttles active video tasks
            view.visibility = View.VISIBLE
            if (isVisible) {
                view.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    )
}

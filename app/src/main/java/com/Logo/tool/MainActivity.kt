package com.Logo.tool

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Logo.tool.ui.AppStrings
import com.Logo.tool.ui.EditorWebViewScreen
import com.Logo.tool.ui.RootToolScreen
import com.Logo.tool.ui.theme.LocalLogoColors
import com.Logo.tool.ui.theme.LogoToolTheme
import com.Logo.tool.util.FileUtils
import com.Logo.tool.viewmodel.MainTab
import com.Logo.tool.viewmodel.MainViewModel
import com.Logo.tool.viewmodel.ThemePreference
import com.Logo.tool.viewmodel.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PendingSafSaveRequest(
    val sourceFile: File,
    val suggestedName: String,
    val deleteAfterSave: Boolean
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = when (state.themePref) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            val copy = AppStrings.forLang(state.language)

            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val rootListState = rememberLazyListState()

            var webViewRef by remember { mutableStateOf<WebView?>(null) }
            var pendingSafSave by remember { mutableStateOf<PendingSafSaveRequest?>(null) }

            val saveSafLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("*/*")
            ) { destUri: Uri? ->
                val req = pendingSafSave
                pendingSafSave = null
                if (req == null) return@rememberLauncherForActivityResult

                if (destUri == null) {
                    if (req.deleteAfterSave) req.sourceFile.delete()
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = copy.msgSaveCancelled,
                            duration = SnackbarDuration.Short
                        )
                    }
                    return@rememberLauncherForActivityResult
                }

                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            FileUtils.writeFileToUri(this@MainActivity, req.sourceFile, destUri)
                            if (req.deleteAfterSave) req.sourceFile.delete()
                        }
                        viewModel.appendLog("${copy.msgSavedPrefix}${req.suggestedName}")
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = "${copy.msgSavedPrefix}${req.suggestedName}",
                            duration = SnackbarDuration.Short
                        )
                    } catch (t: Throwable) {
                        if (req.deleteAfterSave) req.sourceFile.delete()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = t.message ?: "Gagal menyimpan file",
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                    }
                }
            }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UiEvent.InjectToWebView -> {
                            val wv = webViewRef
                            if (wv != null) {
                                viewModel.streamFileToWebView(event.payload) { js ->
                                    wv.evaluateJavascript(js, null)
                                }
                            }
                        }
                        is UiEvent.SyncWebTheme -> {
                            val mode = if (event.isDark) "dark" else "light"
                            webViewRef?.evaluateJavascript(
                                "window.applyTheme && window.applyTheme('$mode');",
                                null
                            )
                        }
                        is UiEvent.SyncWebLang -> {
                            val l = if (event.lang == "en") "en" else "id"
                            webViewRef?.evaluateJavascript(
                                "window.setLang && window.setLang('$l');",
                                null
                            )
                        }
                        is UiEvent.EvalWebJs -> {
                            webViewRef?.evaluateJavascript(event.script, null)
                        }
                        is UiEvent.SetKeepScreenOn -> {
                            if (event.enabled) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                        is UiEvent.ShareFile -> {
                            FileUtils.shareFile(
                                this@MainActivity,
                                event.file,
                                event.displayName,
                                event.mimeType
                            )
                        }
                        is UiEvent.PromptSafSave -> {
                            pendingSafSave = PendingSafSaveRequest(
                                sourceFile = event.sourceFile,
                                suggestedName = event.suggestedName,
                                deleteAfterSave = event.deleteAfterSave
                            )
                            saveSafLauncher.launch(event.suggestedName)
                        }
                        is UiEvent.ScrollToFlashCard -> {
                            rootListState.animateScrollToItem(3)
                        }
                        is UiEvent.ShowMessage -> {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = event.text,
                                withDismissAction = event.isError,
                                duration = if (event.isError) SnackbarDuration.Long else SnackbarDuration.Short
                            )
                        }
                    }
                }
            }

            LogoToolTheme(darkTheme = isDarkTheme) {
                val c = LocalLogoColors.current
                Scaffold(
                    containerColor = c.bg,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (state.activeTab == MainTab.ROOT) {
                            TopAppBar(
                                title = { Text(copy.appTitle) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = c.surface,
                                    titleContentColor = c.ink
                                ),
                                actions = {
                                    IconButton(
                                        onClick = { viewModel.toggleLanguage() },
                                        modifier = Modifier.testTag("language_toggle_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Language,
                                            contentDescription = "ID / EN",
                                            tint = c.accent
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleTheme(isDarkTheme) },
                                        modifier = Modifier.testTag("theme_toggle_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                            contentDescription = "Theme",
                                            tint = c.ink
                                        )
                                    }
                                }
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = c.surface,
                            contentColor = c.ink
                        ) {
                            NavigationBarItem(
                                selected = state.activeTab == MainTab.EDITOR,
                                onClick = { viewModel.selectTab(MainTab.EDITOR) },
                                icon = { Icon(Icons.Filled.Build, contentDescription = copy.tabEditor) },
                                label = { Text(copy.tabEditor) },
                                modifier = Modifier.testTag("nav_editor_tab"),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = c.accent,
                                    selectedTextColor = c.accent,
                                    indicatorColor = c.accent.copy(alpha = 0.14f)
                                )
                            )
                            NavigationBarItem(
                                selected = state.activeTab == MainTab.ROOT,
                                onClick = { viewModel.selectTab(MainTab.ROOT) },
                                icon = { Icon(Icons.Filled.Terminal, contentDescription = copy.tabRoot) },
                                label = { Text(copy.tabRoot) },
                                modifier = Modifier.testTag("nav_root_tab"),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = c.accent,
                                    selectedTextColor = c.accent,
                                    indicatorColor = c.accent.copy(alpha = 0.14f)
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        EditorWebViewScreen(
                            isVisible = state.activeTab == MainTab.EDITOR,
                            isDarkTheme = isDarkTheme,
                            language = state.language,
                            isRootChecker = { viewModel.rootManager.isRootAvailable() },
                            onSetThemeFromHtml = { mode -> viewModel.setThemeFromBridge(mode) },
                            onSetLangFromHtml = { lang -> viewModel.setLanguageFromBridge(lang) },
                            onSetKeepScreenOn = { enabled ->
                                if (enabled) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            },
                            onRequestDumpToEditor = { viewModel.onRequestDumpFromWebBridge() },
                            onReceiveEditedLogoForFlash = { file -> viewModel.onReceiveEditedLogoForFlash(file) },
                            onReceiveFileForSafSave = { file, name, mime ->
                                viewModel.onReceiveFileForSafSave(file, name, mime)
                            },
                            onRequestNativeBootAnim = { optsJson ->
                                viewModel.startNativeBootAnimation(optsJson)
                            },
                            onVideoPickedUri = { uri ->
                                viewModel.lastPickedVideoUri = uri
                            },
                            onWebViewReady = { webView ->
                                webViewRef = webView
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (state.activeTab == MainTab.EDITOR) 1f else 0f)
                        )

                        if (state.activeTab == MainTab.ROOT) {
                            Surface(
                                color = c.bg,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(2f)
                            ) {
                                RootToolScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    copy = copy,
                                    listState = rootListState
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

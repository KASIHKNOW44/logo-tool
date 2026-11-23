package com.Logo.tool

import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import java.io.File
import java.io.FileInputStream

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
            var webViewRef by remember { mutableStateOf<WebView?>(null) }
            var pendingSaveSourceFile by remember { mutableStateOf<File?>(null) }

            val saveDocLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { destUri: Uri? ->
                val src = pendingSaveSourceFile
                pendingSaveSourceFile = null
                if (destUri != null && src != null && src.exists()) {
                    runCatching {
                        contentResolver.openOutputStream(destUri)?.use { out ->
                            FileInputStream(src).use { inp -> inp.copyTo(out, 65536) }
                        }
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
                            webViewRef?.evaluateJavascript(
                                "window.__ltSyncTheme && window.__ltSyncTheme(${event.isDark});",
                                null
                            )
                        }
                        is UiEvent.ShareFile -> {
                            FileUtils.shareFile(
                                this@MainActivity,
                                event.file,
                                event.displayName,
                                event.mimeType
                            )
                        }
                        is UiEvent.PromptSaveDocument -> {
                            pendingSaveSourceFile = event.sourceFile
                            saveDocLauncher.launch(event.suggestedName)
                        }
                        is UiEvent.ShowMessage -> {
                            snackbarHostState.showSnackbar(event.text)
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
                            isRootChecker = { viewModel.rootManager.isRootAvailable() },
                            onSavedFile = { savedPath, cacheCopy, fileName ->
                                viewModel.onWebDownloadSaved(savedPath, cacheCopy, fileName)
                            },
                            onRequestDumpToEditor = {
                                viewModel.onRequestDumpFromWebBridge()
                            },
                            onReceiveEditedLogoForFlash = { file ->
                                viewModel.onReceiveEditedLogoFromBridge(file)
                            },
                            onWebViewReady = { webView ->
                                webViewRef = webView
                            }
                        )

                        if (state.activeTab == MainTab.ROOT) {
                            RootToolScreen(
                                state = state,
                                viewModel = viewModel,
                                copy = copy
                            )
                        }
                    }
                }
            }
        }
    }
}

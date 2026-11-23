package com.Logo.tool.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Logo.tool.media.NativeBootAnimOptions
import com.Logo.tool.media.VideoFrameExtractor
import com.Logo.tool.root.BackupEntry
import com.Logo.tool.root.DumpResult
import com.Logo.tool.root.FlashMode
import com.Logo.tool.root.FlashResult
import com.Logo.tool.root.PartitionInfo
import com.Logo.tool.root.RootManager
import com.Logo.tool.root.SlotTarget
import com.Logo.tool.ui.AppStrings
import com.Logo.tool.util.FileUtils
import com.Logo.tool.util.MtkValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainTab {
    EDITOR,
    ROOT
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class SelectedImageFile(
    val file: File,
    val displayName: String,
    val validation: MtkValidationResult
)

data class WebInjectPayload(
    val kind: String,
    val fileName: String,
    val file: File
)

sealed interface UiEvent {
    data class InjectToWebView(val payload: WebInjectPayload) : UiEvent
    data class SyncWebTheme(val isDark: Boolean) : UiEvent
    data class SyncWebLang(val lang: String) : UiEvent
    data class EvalWebJs(val script: String) : UiEvent
    data class SetKeepScreenOn(val enabled: Boolean) : UiEvent
    data class ShareFile(val file: File, val displayName: String, val mimeType: String) : UiEvent
    data class PromptSafSave(
        val sourceFile: File,
        val suggestedName: String,
        val mimeType: String,
        val deleteAfterSave: Boolean
    ) : UiEvent
    data object ScrollToFlashCard : UiEvent
    data class ShowMessage(val text: String, val isError: Boolean = false) : UiEvent
}

data class MainUiState(
    val activeTab: MainTab = MainTab.EDITOR,
    val themePref: ThemePreference = ThemePreference.SYSTEM,
    val language: String = "id",
    val isBusy: Boolean = false,
    val isRootAvailable: Boolean = false,
    val partitionInfo: PartitionInfo? = null,
    val partitionError: String? = null,
    val dumpSlotTarget: SlotTarget = SlotTarget.ACTIVE,
    val trimTrailingFf: Boolean = true,
    val lastDumpResult: DumpResult? = null,
    val lastEditedLogoFile: SelectedImageFile? = null,
    val pendingFlashFile: SelectedImageFile? = null,
    val flashMode: FlashMode = FlashMode.BACKUP_AND_FLASH,
    val flashBothSlots: Boolean = false,
    val lastFlashResult: FlashResult? = null,
    val backups: List<BackupEntry> = emptyList(),
    val backupLoadError: String? = null,
    val logs: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("logo_tool_prefs", Context.MODE_PRIVATE)
    val rootManager = RootManager(application.applicationContext)

    private val _uiState = MutableStateFlow(
        MainUiState(
            themePref = runCatching {
                ThemePreference.valueOf(prefs.getString("theme_pref", ThemePreference.SYSTEM.name)!!)
            }.getOrDefault(ThemePreference.SYSTEM),
            language = prefs.getString("language", "id") ?: "id",
            flashMode = runCatching {
                FlashMode.valueOf(prefs.getString("flash_mode", FlashMode.BACKUP_AND_FLASH.name)!!)
            }.getOrDefault(FlashMode.BACKUP_AND_FLASH)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    @Volatile
    var lastPickedVideoUri: Uri? = null

    init {
        refreshRootEnvironment()
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun toggleTheme(currentlyDark: Boolean) {
        val nextMode = if (currentlyDark) "light" else "dark"
        setThemeFromBridge(nextMode)
    }

    fun setThemeFromBridge(mode: String) {
        val pref = when (mode.lowercase(Locale.US)) {
            "dark" -> ThemePreference.DARK
            "light" -> ThemePreference.LIGHT
            else -> ThemePreference.SYSTEM
        }
        prefs.edit().putString("theme_pref", pref.name).apply()
        _uiState.update { it.copy(themePref = pref) }
        if (pref != ThemePreference.SYSTEM) {
            _events.tryEmit(UiEvent.SyncWebTheme(pref == ThemePreference.DARK))
        }
    }

    fun toggleLanguage() {
        val next = if (_uiState.value.language == "id") "en" else "id"
        setLanguageFromBridge(next)
    }

    fun setLanguageFromBridge(lang: String) {
        val clean = if (lang.lowercase(Locale.US) == "en") "en" else "id"
        if (_uiState.value.language == clean) return
        prefs.edit().putString("language", clean).apply()
        _uiState.update { it.copy(language = clean) }
        _events.tryEmit(UiEvent.SyncWebLang(clean))
    }

    fun setDumpSlotTarget(target: SlotTarget) {
        _uiState.update { it.copy(dumpSlotTarget = target) }
    }

    fun setTrimTrailingFf(enabled: Boolean) {
        _uiState.update { it.copy(trimTrailingFf = enabled) }
    }

    fun setFlashMode(mode: FlashMode) {
        prefs.edit().putString("flash_mode", mode.name).apply()
        _uiState.update { it.copy(flashMode = mode) }
    }

    fun setFlashBothSlots(both: Boolean) {
        _uiState.update { it.copy(flashBothSlots = both) }
    }

    fun clearPendingFlashFile() {
        _uiState.update { it.copy(pendingFlashFile = null, lastFlashResult = null) }
    }

    fun appendLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $message"
        _uiState.update { state ->
            state.copy(logs = (state.logs + line).takeLast(400))
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun emitMessage(text: String, isError: Boolean = false) {
        _events.tryEmit(UiEvent.ShowMessage(text, isError))
    }

    fun refreshRootEnvironment() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val isRoot = withContext(Dispatchers.IO) { rootManager.isRootAvailable() }
                if (!isRoot) {
                    appendLog("Root tidak terdeteksi atau ditolak.")
                    _uiState.update {
                        it.copy(
                            isRootAvailable = false,
                            partitionInfo = null,
                            partitionError = "Akses root tidak tersedia.",
                            backups = emptyList()
                        )
                    }
                    return@launch
                }

                appendLog("Akses root aktif. Mendeteksi partisi logo...")
                val partRes = withContext(Dispatchers.IO) {
                    rootManager.detectPartitionInfo { msg -> appendLog(msg) }
                }
                val partInfo = partRes.getOrNull()
                val partErr = partRes.exceptionOrNull()?.message
                if (partErr != null) {
                    appendLog("ERROR deteksi partisi: $partErr")
                }

                val bkRes = withContext(Dispatchers.IO) {
                    rootManager.backupStore.listBackups()
                }
                _uiState.update {
                    it.copy(
                        isRootAvailable = true,
                        partitionInfo = partInfo,
                        partitionError = partErr,
                        backups = bkRes.getOrDefault(emptyList()),
                        backupLoadError = bkRes.exceptionOrNull()?.message
                    )
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            val bkRes = withContext(Dispatchers.IO) { rootManager.backupStore.listBackups() }
            _uiState.update {
                it.copy(
                    backups = bkRes.getOrDefault(emptyList()),
                    backupLoadError = bkRes.exceptionOrNull()?.message
                )
            }
        }
    }

    fun onReceiveFileForSafSave(file: File, suggestedName: String, mimeType: String) {
        appendLog("Siapkan simpan SAF: $suggestedName (${FileUtils.formatBytes(file.length())})")
        if (suggestedName.endsWith(".img", ignoreCase = true)) {
            viewModelScope.launch(Dispatchers.IO) {
                val copyDir = File(getApplication<Application>().cacheDir, "last_edited").apply { mkdirs() }
                val copyFile = File(copyDir, suggestedName)
                file.copyTo(copyFile, overwrite = true)
                val maxSize = _uiState.value.partitionInfo?.sizeBytes
                val validation = FileUtils.validateMtkLogoFile(copyFile, maxSize)
                _uiState.update {
                    it.copy(lastEditedLogoFile = SelectedImageFile(copyFile, suggestedName, validation))
                }
            }
        }
        _events.tryEmit(
            UiEvent.PromptSafSave(
                sourceFile = file,
                suggestedName = suggestedName,
                mimeType = mimeType,
                deleteAfterSave = true
            )
        )
    }

    fun onReceiveEditedLogoForFlash(file: File) {
        viewModelScope.launch {
            val maxSize = _uiState.value.partitionInfo?.sizeBytes
            val validation = withContext(Dispatchers.IO) {
                FileUtils.validateMtkLogoFile(file, maxSize)
            }
            val selected = SelectedImageFile(file, file.name, validation)
            val copy = AppStrings.forLang(_uiState.value.language)
            val isRoot = _uiState.value.isRootAvailable
            _uiState.update {
                it.copy(
                    lastEditedLogoFile = selected,
                    pendingFlashFile = selected,
                    lastFlashResult = null,
                    activeTab = MainTab.ROOT
                )
            }
            appendLog(
                "File hasil edit diterima: ${file.name} (${FileUtils.formatBytes(file.length())}, SHA-256: ${validation.sha256.take(12)}...)"
            )
            _events.emit(UiEvent.ScrollToFlashCard)
            if (!isRoot) {
                _events.emit(UiEvent.ShowMessage(copy.msgFlashReadyNoRoot, isError = true))
            } else if (!validation.isValid) {
                _events.emit(UiEvent.ShowMessage(validation.errorReason ?: "File tidak valid", isError = true))
            } else {
                _events.emit(UiEvent.ShowMessage(copy.msgFlashReady))
            }
        }
    }

    fun onRequestDumpFromWebBridge() {
        val state = _uiState.value
        if (state.isBusy) return
        if (!state.isRootAvailable || state.partitionInfo == null) {
            _events.tryEmit(UiEvent.ShowMessage("Root atau partisi logo belum tersedia. Cek tab Root Tool.", isError = true))
            _uiState.update { it.copy(activeTab = MainTab.ROOT) }
            return
        }
        dumpLogo(openInEditorAfter = true)
    }

    fun dumpLogo(openInEditorAfter: Boolean = false) {
        val info = _uiState.value.partitionInfo ?: return
        val slotTarget = _uiState.value.dumpSlotTarget
        val trim = _uiState.value.trimTrailingFf

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val res = withContext(Dispatchers.IO) {
                    rootManager.dumpLogoToCache(info, slotTarget, trim) { msg -> appendLog(msg) }
                }
                res.onSuccess { dump ->
                    val validation = withContext(Dispatchers.IO) {
                        FileUtils.validateMtkLogoFile(dump.cacheFile, info.sizeBytes)
                    }
                    val sel = SelectedImageFile(dump.cacheFile, dump.suggestedName, validation)
                    _uiState.update {
                        it.copy(
                            lastDumpResult = dump,
                            pendingFlashFile = it.pendingFlashFile ?: sel
                        )
                    }
                    if (openInEditorAfter) {
                        _uiState.update { it.copy(activeTab = MainTab.EDITOR) }
                        _events.emit(
                            UiEvent.InjectToWebView(
                                WebInjectPayload("logo", dump.suggestedName, dump.cacheFile)
                            )
                        )
                    } else {
                        _events.emit(
                            UiEvent.PromptSafSave(
                                sourceFile = dump.cacheFile,
                                suggestedName = dump.suggestedName,
                                mimeType = "application/octet-stream",
                                deleteAfterSave = false // keep cache copy for "Buka di editor"
                            )
                        )
                    }
                }.onFailure { err ->
                    appendLog("GAGAL dump: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Dump gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun saveLastDumpViaSaf() {
        val dump = _uiState.value.lastDumpResult ?: return
        _events.tryEmit(
            UiEvent.PromptSafSave(
                sourceFile = dump.cacheFile,
                suggestedName = dump.suggestedName,
                mimeType = "application/octet-stream",
                deleteAfterSave = false
            )
        )
    }

    fun openLastDumpInEditor() {
        val dump = _uiState.value.lastDumpResult ?: return
        _uiState.update { it.copy(activeTab = MainTab.EDITOR) }
        _events.tryEmit(
            UiEvent.InjectToWebView(
                WebInjectPayload("logo", dump.suggestedName, dump.cacheFile)
            )
        )
    }

    fun selectFlashFileFromUri(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val selected = withContext(Dispatchers.IO) {
                    val name = FileUtils.getDisplayName(app, uri)
                    val dir = File(app.cacheDir, "selected_flash").apply { mkdirs() }
                    val target = File(dir, name)
                    FileUtils.copyUriToFile(app, uri, target)
                    val maxSize = _uiState.value.partitionInfo?.sizeBytes
                    val validation = FileUtils.validateMtkLogoFile(target, maxSize)
                    SelectedImageFile(target, name, validation)
                }
                _uiState.update { it.copy(pendingFlashFile = selected, lastFlashResult = null) }
                if (selected.validation.isValid) {
                    appendLog("File dipilih: ${selected.displayName} (${FileUtils.formatBytes(selected.validation.sizeBytes)}, SHA-256: ${selected.validation.sha256.take(12)}...)")
                } else {
                    appendLog("VALIDASI GAGAL (${selected.displayName}): ${selected.validation.errorReason}")
                }
            } catch (t: Throwable) {
                appendLog("Gagal membaca file: ${t.message}")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun useLastEditedForFlash() {
        val edited = _uiState.value.lastEditedLogoFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val maxSize = _uiState.value.partitionInfo?.sizeBytes
            val revalidated = FileUtils.validateMtkLogoFile(edited.file, maxSize)
            val updated = edited.copy(validation = revalidated)
            _uiState.update { it.copy(pendingFlashFile = updated, lastFlashResult = null) }
            appendLog("Memakai hasil edit terakhir: ${updated.displayName}")
        }
    }

    fun executeFlash() {
        val state = _uiState.value
        val info = state.partitionInfo ?: return
        val selected = state.pendingFlashFile ?: return
        if (!selected.validation.isValid) return

        val slotTarget = if (info.isAbDevice && state.flashBothSlots) {
            SlotTarget.BOTH
        } else {
            SlotTarget.ACTIVE
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastFlashResult = null) }
            try {
                val res = withContext(Dispatchers.IO) {
                    rootManager.flashLogo(
                        info = info,
                        sourceFile = selected.file,
                        slotTarget = slotTarget,
                        mode = state.flashMode
                    ) { msg -> appendLog(msg) }
                }
                res.onSuccess { flashResult ->
                    _uiState.update { it.copy(lastFlashResult = flashResult) }
                    refreshBackups()
                }.onFailure { err ->
                    appendLog("FLASH DIBATALKAN/GAGAL: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Flash gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun restoreLatestCreatedBackup() {
        val backups = _uiState.value.backups
        val latest = _uiState.value.lastFlashResult?.createdBackups?.firstOrNull()
            ?: backups.firstOrNull()
            ?: return
        val targetSlot = when (latest.slotLabel.lowercase(Locale.US)) {
            "slot a", "a" -> SlotTarget.SLOT_A
            "slot b", "b" -> SlotTarget.SLOT_B
            else -> SlotTarget.ACTIVE
        }
        restoreBackup(latest, targetSlot)
    }

    fun backupNow() {
        val info = _uiState.value.partitionInfo ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val targets = rootManager.resolveTargetsForSlot(info, SlotTarget.ACTIVE).getOrElse {
                    appendLog("ERROR: ${it.message}")
                    return@launch
                }
                val (slotLabel, partPath) = targets.first()
                val res = withContext(Dispatchers.IO) {
                    rootManager.backupStore.createBackupFromPartition(partPath, slotLabel) { msg ->
                        appendLog(msg)
                    }
                }
                res.onSuccess {
                    refreshBackups()
                }.onFailure { err ->
                    appendLog("GAGAL backup: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Backup gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun importBackupFromUri(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val res = withContext(Dispatchers.IO) {
                    val name = FileUtils.getDisplayName(app, uri)
                    val tmp = File(app.cacheDir, "import_$name")
                    FileUtils.copyUriToFile(app, uri, tmp)
                    val maxSize = _uiState.value.partitionInfo?.sizeBytes
                    val valRes = FileUtils.validateMtkLogoFile(tmp, maxSize)
                    if (!valRes.isValid) {
                        tmp.delete()
                        return@withContext Result.failure<BackupEntry>(
                            IllegalArgumentException(valRes.errorReason ?: "File tidak valid.")
                        )
                    }
                    val imp = rootManager.backupStore.importBackupFromCacheFile(tmp, valRes.sha256)
                    tmp.delete()
                    imp
                }
                res.onSuccess { entry ->
                    appendLog("Backup diimpor ke /data/adb/logo_tool/: ${entry.fileName}")
                    refreshBackups()
                }.onFailure { err ->
                    appendLog("GAGAL impor backup: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Impor gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun restoreBackup(backup: BackupEntry, targetSlot: SlotTarget) {
        val info = _uiState.value.partitionInfo ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastFlashResult = null) }
            try {
                val res = withContext(Dispatchers.IO) {
                    rootManager.restoreBackup(info, backup, targetSlot) { msg -> appendLog(msg) }
                }
                res.onSuccess { flashRes ->
                    _uiState.update { it.copy(lastFlashResult = flashRes) }
                }.onFailure { err ->
                    appendLog("GAGAL restore: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Restore gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun deleteBackup(backup: BackupEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val res = withContext(Dispatchers.IO) {
                    rootManager.backupStore.deleteBackup(backup)
                }
                res.onSuccess {
                    appendLog("Backup dihapus: ${backup.fileName}")
                    refreshBackups()
                }.onFailure { err ->
                    appendLog("GAGAL hapus backup: ${err.message}")
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun exportBackupViaSaf(backup: BackupEntry) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val copied = withContext(Dispatchers.IO) {
                    val dest = File(app.cacheDir, "export_tmp/${backup.fileName}")
                    rootManager.backupStore.copyBackupToCache(backup, dest)
                }
                copied.onSuccess { file ->
                    _events.emit(
                        UiEvent.PromptSafSave(
                            sourceFile = file,
                            suggestedName = backup.fileName,
                            mimeType = "application/octet-stream",
                            deleteAfterSave = true
                        )
                    )
                }.onFailure { err ->
                    appendLog("GAGAL ekspor backup: ${err.message}")
                    _events.emit(UiEvent.ShowMessage(err.message ?: "Ekspor gagal", isError = true))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun openBackupInEditor(backup: BackupEntry) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val copied = withContext(Dispatchers.IO) {
                    val dest = File(app.cacheDir, "bridge_in/${backup.fileName}")
                    rootManager.backupStore.copyBackupToCache(backup, dest)
                }
                copied.onSuccess { file ->
                    _uiState.update { it.copy(activeTab = MainTab.EDITOR) }
                    _events.emit(UiEvent.InjectToWebView(WebInjectPayload("logo", backup.fileName, file)))
                }.onFailure { err ->
                    appendLog("GAGAL buka backup di editor: ${err.message}")
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun shareBackup(backup: BackupEntry) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val copied = withContext(Dispatchers.IO) {
                    val dest = File(app.cacheDir, "share/${backup.fileName}")
                    rootManager.backupStore.copyBackupToCache(backup, dest)
                }
                copied.onSuccess { file ->
                    _events.emit(UiEvent.ShareFile(file, backup.fileName, "application/octet-stream"))
                }.onFailure { err ->
                    appendLog("GAGAL bagikan backup: ${err.message}")
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun startNativeBootAnimation(optionsJson: String): String {
        val videoUri = lastPickedVideoUri ?: return "ERR_FALLBACK: video URI tidak tersedia"
        val json = runCatching { JSONObject(optionsJson) }.getOrNull()
            ?: return "ERR_FALLBACK: JSON opsi tidak valid"

        val opts = NativeBootAnimOptions(
            width = json.optInt("width", 720).coerceAtLeast(16),
            height = json.optInt("height", 1600).coerceAtLeast(16),
            fps = json.optInt("fps", 20).coerceIn(1, 60),
            secs = json.optDouble("secs", 6.0).coerceAtLeast(0.5),
            fitMode = json.optString("fitMode", "cover"),
            partName = json.optString("partName", "part0").ifBlank { "part0" }
        )

        val app = getApplication<Application>()
        viewModelScope.launch {
            _events.emit(UiEvent.SetKeepScreenOn(true))
            try {
                val outZip = File(app.cacheDir, "pending_save/bootanim_module.zip")
                val res = withContext(Dispatchers.IO) {
                    VideoFrameExtractor.buildBootAnimationModule(
                        context = app,
                        videoUri = videoUri,
                        options = opts,
                        originalBootAnimZip = null,
                        outModuleZip = outZip,
                        onProgress = { cur, tot ->
                            _events.tryEmit(
                                UiEvent.EvalWebJs("window.onNativeProgress && window.onNativeProgress($cur, $tot);")
                            )
                        }
                    )
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("frameCount", res.frameCount)
                    put("decodeMs", res.decodeMs)
                    put("drawMs", res.drawMs)
                    put("encodeMs", res.encodeMs)
                    put("zipMs", res.zipMs)
                    put("totalMs", res.totalMs)
                    put("usedFallback", res.usedFallback)
                }
                _events.emit(
                    UiEvent.EvalWebJs(
                        "window.onNativeBootAnimDone && window.onNativeBootAnimDone(${JSONObject.quote(payload.toString())});"
                    )
                )
                _events.emit(
                    UiEvent.PromptSafSave(
                        sourceFile = outZip,
                        suggestedName = "bootanim_module.zip",
                        mimeType = "application/zip",
                        deleteAfterSave = true
                    )
                )
            } catch (t: Throwable) {
                appendLog("Native bootanim fallback ke JS: ${t.message}")
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", t.message ?: "Native extraction failed")
                }
                _events.emit(
                    UiEvent.EvalWebJs(
                        "window.onNativeBootAnimDone && window.onNativeBootAnimDone(${JSONObject.quote(payload.toString())});"
                    )
                )
            } finally {
                _events.emit(UiEvent.SetKeepScreenOn(false))
            }
        }
        return "STARTED"
    }

    suspend fun streamFileToWebView(
        payload: WebInjectPayload,
        evalJs: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            val safeKind = payload.kind.replace("'", "")
            val safeName = payload.fileName.replace("'", "_")
            evalJs("window.__ltBeginReceive && window.__ltBeginReceive('$safeKind', '$safeName');")
        }
        withContext(Dispatchers.IO) {
            val buf = ByteArray(196608)
            FileInputStream(payload.file).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        evalJs("window.__ltPushChunk && window.__ltPushChunk('$b64');")
                    }
                }
            }
        }
        withContext(Dispatchers.Main) {
            evalJs("window.__ltFinishReceive && window.__ltFinishReceive();")
        }
    }
}

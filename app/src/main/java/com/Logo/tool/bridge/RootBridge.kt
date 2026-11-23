package com.Logo.tool.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import com.Logo.tool.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified @JavascriptInterface exposed as window.AndroidRoot.
 * All UI callbacks are dispatched safely to the main thread via Handler(Looper.getMainLooper()).
 */
class RootBridge(
    private val context: Context,
    private val isRootChecker: () -> Boolean,
    private val onSetTheme: (mode: String) -> Unit,
    private val onSetLang: (lang: String) -> Unit,
    private val onSetKeepScreenOn: (enabled: Boolean) -> Unit,
    private val onRequestDumpToEditor: () -> Unit,
    private val onReceiveEditedLogoForFlash: (file: File) -> Unit,
    private val onReceiveFileForSafSave: (file: File, suggestedName: String, mimeType: String) -> Unit,
    private val onRequestNativeBootAnim: (optionsJson: String) -> String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transferFiles = ConcurrentHashMap<String, File>()

    @JavascriptInterface
    fun isRootAvailable(): Boolean = isRootChecker()

    @JavascriptInterface
    fun setTheme(mode: String) {
        mainHandler.post { onSetTheme(mode) }
    }

    @JavascriptInterface
    fun setLang(lang: String) {
        mainHandler.post { onSetLang(lang) }
    }

    @JavascriptInterface
    fun setKeepScreenOn(enabled: Boolean) {
        mainHandler.post { onSetKeepScreenOn(enabled) }
    }

    @JavascriptInterface
    fun requestLoadFromPhone(): String {
        return try {
            mainHandler.post { onRequestDumpToEditor() }
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun receiveChunk(sessionId: String, index: Int, total: Int, base64Chunk: String): String {
        return try {
            val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val dir = File(context.cacheDir, "pending_flash").apply { mkdirs() }
            val tempFile = transferFiles.getOrPut(safeId) { File(dir, "$safeId.tmp") }
            if (index == 0 && tempFile.exists()) {
                tempFile.delete()
            }
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            FileOutputStream(tempFile, index > 0).use { fos ->
                fos.write(bytes)
            }
            "OK:$index/$total"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun finishTransfer(sessionId: String, fileName: String): String {
        return try {
            val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val tempFile = transferFiles.remove(safeId)
                ?: return "ERR: Sesi transfer tidak ditemukan"
            val dir = File(context.cacheDir, "pending_flash").apply { mkdirs() }
            val finalFile = File(dir, FileUtils.sanitizeFileName(fileName))
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            mainHandler.post { onReceiveEditedLogoForFlash(finalFile) }
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun finishSaveTransfer(sessionId: String, suggestedName: String, mimeType: String): String {
        return try {
            val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val tempFile = transferFiles.remove(safeId)
                ?: return "ERR: Sesi transfer simpan tidak ditemukan"
            val dir = File(context.cacheDir, "pending_save").apply { mkdirs() }
            val safeName = FileUtils.sanitizeFileName(suggestedName)
            val finalFile = File(dir, safeName)
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            mainHandler.post { onReceiveFileForSafSave(finalFile, safeName, mimeType) }
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun createBootAnimationNative(optionsJson: String): String {
        return try {
            onRequestNativeBootAnim(optionsJson)
        } catch (t: Throwable) {
            "ERR_FALLBACK: ${t.message}"
        }
    }
}

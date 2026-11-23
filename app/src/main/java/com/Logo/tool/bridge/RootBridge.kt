package com.Logo.tool.bridge

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import com.Logo.tool.util.FileUtils
import java.io.File
import java.io.FileOutputStream

/**
 * JavascriptInterface exposed as window.AndroidRoot for bidirectional editor <-> root communication.
 */
class RootBridge(
    private val context: Context,
    private val isRootChecker: () -> Boolean,
    private val onRequestDumpToEditor: () -> Unit,
    private val onReceiveEditedLogoForFlash: (file: File) -> Unit
) {
    private var flashFileName: String = "new_logo.img"
    private var tempFlashFile: File? = null
    private var tempOut: FileOutputStream? = null

    @JavascriptInterface
    fun isRootAvailable(): Boolean {
        return isRootChecker()
    }

    @JavascriptInterface
    fun requestLoadFromPhone(): String {
        return try {
            onRequestDumpToEditor()
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun flashBegin(fileName: String): String {
        return try {
            flashFileName = FileUtils.sanitizeFileName(fileName)
            val dir = File(context.cacheDir, "bridge_flash").apply { mkdirs() }
            val target = File(dir, flashFileName)
            tempOut?.close()
            tempFlashFile = target
            tempOut = FileOutputStream(target)
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun flashChunk(base64Chunk: String): String {
        return try {
            val out = tempOut ?: return "ERR: flashBegin() belum dipanggil"
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            out.write(bytes)
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun flashCommit(): String {
        return try {
            tempOut?.flush()
            tempOut?.close()
            tempOut = null
            val file = tempFlashFile ?: return "ERR: file hasil edit tidak ditemukan"
            onReceiveEditedLogoForFlash(file)
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }
}

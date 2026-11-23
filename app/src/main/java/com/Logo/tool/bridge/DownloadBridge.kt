package com.Logo.tool.bridge

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import com.Logo.tool.util.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * JavascriptInterface exposed as window.Android to match logotool.html's built-in dl() hook.
 * Saves downloaded blobs directly to /sdcard/Download/BootLogoTool/.
 */
class DownloadBridge(
    private val context: Context,
    private val onSavedFile: (savedPath: String, cacheCopy: File, fileName: String) -> Unit
) {
    private var currentFileName: String? = null
    private var bufferStream: ByteArrayOutputStream? = null

    @JavascriptInterface
    fun begin(fileName: String): String {
        return try {
            currentFileName = FileUtils.sanitizeFileName(fileName)
            bufferStream = ByteArrayOutputStream()
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun chunk(base64Chunk: String): String {
        return try {
            val stream = bufferStream ?: return "ERR: begin() belum dipanggil"
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            stream.write(bytes)
            "OK"
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    @JavascriptInterface
    fun end(): String {
        return try {
            val name = currentFileName ?: return "ERR: nama file kosong"
            val bytes = bufferStream?.toByteArray() ?: return "ERR: buffer kosong"
            bufferStream = null
            currentFileName = null

            val exportDir = File(context.cacheDir, "editor_exports").apply { mkdirs() }
            val cacheFile = File(exportDir, name)
            FileOutputStream(cacheFile).use { it.write(bytes) }

            val mime = if (name.endsWith(".zip", ignoreCase = true)) {
                "application/zip"
            } else {
                "application/octet-stream"
            }
            val savedPath = FileUtils.saveFileToPublicDownloads(context, cacheFile, name, mime)
            onSavedFile(savedPath, cacheFile, name)
            savedPath
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }
}

package com.Logo.tool.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MtkValidationResult(
    val isValid: Boolean,
    val errorReason: String? = null,
    val sizeBytes: Long = 0L,
    val sha256: String = ""
)

object FileUtils {
    private val ALLOWED_LOGO_NAMES = Regex("^logo(_[ab])?$")

    fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    fun sanitizeModel(model: String?): String {
        val cleaned = (model ?: "device")
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return if (cleaned.isEmpty()) "device" else cleaned
    }

    fun sanitizeFileName(name: String): String {
        val base = File(name).name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return if (base.isBlank()) "file.bin" else base
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun isWhitelistedPartitionName(partitionOrLinkPath: String): Boolean {
        val baseName = File(partitionOrLinkPath.trim()).name
        return ALLOWED_LOGO_NAMES.matches(baseName)
    }

    fun isWhitelistedBlockDevice(linkPath: String, resolvedPath: String?): Boolean {
        val linkOk = isWhitelistedPartitionName(linkPath)
        val resolvedOk = if (!resolvedPath.isNullOrBlank()) {
            val rName = File(resolvedPath).name
            rName.startsWith("mmcblk") || rName.startsWith("sd") || rName.startsWith("dm-") ||
                ALLOWED_LOGO_NAMES.matches(rName)
        } else {
            true
        }
        return linkOk && resolvedOk
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun validateMtkLogoFile(file: File, partitionSize: Long? = null): MtkValidationResult {
        if (!file.exists() || !file.isFile) {
            return MtkValidationResult(false, "File tidak ditemukan: ${file.name}")
        }
        val length = file.length()
        if (length <= 0L) {
            return MtkValidationResult(false, "File kosong (0 byte).")
        }
        if (length < 600L) {
            return MtkValidationResult(false, "Ukuran file terlalu kecil ($length byte), minimal 600 byte untuk header MTK.")
        }
        if (partitionSize != null && partitionSize > 0L && length > partitionSize) {
            return MtkValidationResult(
                false,
                "Ukuran file (${formatBytes(length)}) melebihi ukuran partisi (${formatBytes(partitionSize)})."
            )
        }

        val header = ByteArray(16)
        FileInputStream(file).use { fis ->
            val read = fis.read(header)
            if (read < 16) {
                return MtkValidationResult(false, "Gagal membaca header MTK (kurang dari 16 byte).")
            }
        }

        val magicOk = header[0] == 0x88.toByte() &&
            header[1] == 0x16.toByte() &&
            header[2] == 0x88.toByte() &&
            header[3] == 0x58.toByte()
        if (!magicOk) {
            val got = "%02X %02X %02X %02X".format(header[0], header[1], header[2], header[3])
            return MtkValidationResult(
                false,
                "Magic header MTK tidak cocok (ditemukan: $got, wajib: 88 16 88 58)."
            )
        }

        val nameBytes = header.copyOfRange(8, 12)
        val nameStr = String(nameBytes, Charsets.US_ASCII)
        if (nameStr != "logo") {
            return MtkValidationResult(
                false,
                "Nama partisi di offset 0x08 bukan 'logo' (ditemukan: '$nameStr')."
            )
        }

        val hash = sha256(file)
        return MtkValidationResult(
            isValid = true,
            sizeBytes = length,
            sha256 = hash
        )
    }

    fun trimMtkTrailingPadding(file: File): Long {
        val len = file.length()
        if (len < 600L) return len

        RandomAccessFile(file, "rw").use { raf ->
            val hdr = ByteArray(520)
            raf.seek(0L)
            val read = raf.read(hdr)
            if (read == 520 &&
                hdr[0] == 0x88.toByte() &&
                hdr[1] == 0x16.toByte() &&
                hdr[2] == 0x88.toByte() &&
                hdr[3] == 0x58.toByte()
            ) {
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val tot = bb.getInt(516).toLong() and 0xFFFFFFFFL
                val payloadEnd = 512L + tot
                val alignedPayloadEnd = payloadEnd + ((512L - (payloadEnd % 512L)) % 512L)
                if (alignedPayloadEnd in 600L..len && isRangeOnlyPadding(raf, alignedPayloadEnd, len)) {
                    raf.setLength(alignedPayloadEnd)
                    return alignedPayloadEnd
                }
            }

            val chunk = ByteArray(65536)
            var pos = len
            var lastNonPad = len
            while (pos > 0L) {
                val start = maxOf(0L, pos - chunk.size)
                val count = (pos - start).toInt()
                raf.seek(start)
                raf.readFully(chunk, 0, count)
                var foundNonPad = false
                for (i in count - 1 downTo 0) {
                    val b = chunk[i]
                    if (b != 0xFF.toByte() && b != 0x00.toByte()) {
                        lastNonPad = start + i + 1L
                        foundNonPad = true
                        break
                    }
                }
                if (foundNonPad) break
                pos = start
            }

            val aligned = maxOf(512L, lastNonPad + ((512L - (lastNonPad % 512L)) % 512L))
            if (aligned < len) {
                raf.setLength(aligned)
                return aligned
            }
        }
        return len
    }

    private fun isRangeOnlyPadding(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        val buf = ByteArray(65536)
        var pos = start
        raf.seek(pos)
        while (pos < end) {
            val toRead = minOf(buf.size.toLong(), end - pos).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n <= 0) break
            for (i in 0 until n) {
                val b = buf[i]
                if (b != 0xFF.toByte() && b != 0x00.toByte()) return false
            }
            pos += n
        }
        return true
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return sanitizeFileName(name)
                }
            }
        }
        return sanitizeFileName(uri.lastPathSegment ?: "selected_file.img")
    }

    fun copyUriToFile(context: Context, uri: Uri, destFile: File): File {
        destFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        } ?: throw IllegalStateException("Tidak dapat membuka file yang dipilih.")
        return destFile
    }

    fun writeFileToUri(context: Context, sourceFile: File, destUri: Uri) {
        context.contentResolver.openOutputStream(destUri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output, bufferSize = 65536)
            }
        } ?: throw IllegalStateException("Tidak dapat menulis ke lokasi SAF yang dipilih.")
    }

    fun shareFile(context: Context, sourceFile: File, shareDisplayName: String, mimeType: String) {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(shareDir, sanitizeFileName(shareDisplayName))
        if (sourceFile.absolutePath != target.absolutePath) {
            FileInputStream(sourceFile).use { inp ->
                FileOutputStream(target).use { out -> inp.copyTo(out, 65536) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, shareDisplayName))
    }
}

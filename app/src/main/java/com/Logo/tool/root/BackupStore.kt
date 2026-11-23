package com.Logo.tool.root

import android.content.Context
import android.os.Process
import com.Logo.tool.util.FileUtils
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupStore(private val context: Context) {

    companion object {
        const val BACKUP_DIR = "/data/adb/logo_tool"
    }

    fun ensureBackupDir(): Result<Unit> {
        val dirQ = FileUtils.shellQuote(BACKUP_DIR)
        val res = Shell.cmd(
            "mkdir -p $dirQ",
            "chmod 700 $dirQ",
            "chown root:root $dirQ || true",
            "test -d $dirQ && touch $dirQ/.rw_probe && rm -f $dirQ/.rw_probe"
        ).exec()
        return if (res.isSuccess) {
            Result.success(Unit)
        } else {
            val err = res.err.joinToString("\n").ifBlank { "Tidak dapat menulis ke $BACKUP_DIR" }
            Result.failure(IllegalStateException("Folder backup $BACKUP_DIR tidak bisa diakses/ditulis: $err"))
        }
    }

    fun listBackups(): Result<List<BackupEntry>> {
        if (!Shell.getShell().isRoot) {
            return Result.failure(IllegalStateException("Akses root tidak tersedia."))
        }
        val dirQ = FileUtils.shellQuote(BACKUP_DIR)
        val probe = Shell.cmd("test -d $dirQ").exec()
        if (!probe.isSuccess) {
            return Result.success(emptyList())
        }

        val script = """
            for f in $dirQ/*.img; do
              [ -f "${'$'}f" ] || continue
              sz=${'$'}(wc -c < "${'$'}f" 2>/dev/null | tr -d ' ')
              mt=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null || echo 0)
              act=${'$'}(sha256sum "${'$'}f" 2>/dev/null | awk '{print ${'$'}1}')
              exp=""
              if [ -f "${'$'}f.sha256" ]; then
                exp=${'$'}(head -n 1 "${'$'}f.sha256" 2>/dev/null | awk '{print ${'$'}1}')
              fi
              printf '%s|%s|%s|%s|%s\n' "${'$'}f" "${'$'}sz" "${'$'}mt" "${'$'}act" "${'$'}exp"
            done
        """.trimIndent()

        val res = Shell.cmd(script).exec()
        if (!res.isSuccess && res.out.isEmpty()) {
            return Result.failure(IllegalStateException("Gagal membaca daftar backup di $BACKUP_DIR"))
        }

        val entries = res.out.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 5) return@mapNotNull null
            val fullPath = parts[0].trim()
            if (fullPath.isBlank() || !fullPath.endsWith(".img")) return@mapNotNull null
            val fileName = File(fullPath).name
            val sizeBytes = parts[1].trim().toLongOrNull() ?: 0L
            val mtime = parts[2].trim().toLongOrNull() ?: 0L
            val actualHash = parts[3].trim().lowercase(Locale.US).ifBlank { null }
            val expectedHash = parts[4].trim().lowercase(Locale.US).ifBlank { null }

            val status = when {
                expectedHash == null -> BackupHashStatus.NO_HASH_FILE
                actualHash != null && actualHash == expectedHash -> BackupHashStatus.MATCH
                else -> BackupHashStatus.MISMATCH
            }

            BackupEntry(
                fileName = fileName,
                fullPath = fullPath,
                sizeBytes = sizeBytes,
                modifiedEpochSec = mtime,
                slotLabel = parseSlotFromFileName(fileName),
                expectedSha256 = expectedHash,
                actualSha256 = actualHash,
                hashStatus = status
            )
        }.sortedByDescending { it.modifiedEpochSec }

        return Result.success(entries)
    }

    fun createBackupFromPartition(
        partitionPath: String,
        slotLabel: String,
        onLog: (String) -> Unit
    ): Result<BackupEntry> {
        if (!FileUtils.isWhitelistedPartitionName(partitionPath)) {
            return Result.failure(SecurityException("Partisi ditolak oleh whitelist: $partitionPath"))
        }
        ensureBackupDir().onFailure { return Result.failure(it) }

        val cleanSlot = when (slotLabel.lowercase(Locale.US)) {
            "a", "_a" -> "a"
            "b", "_b" -> "b"
            else -> "nonab"
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "logo_backup_${cleanSlot}_$ts.img"
        val destPath = "$BACKUP_DIR/$fileName"
        val hashPath = "$destPath.sha256"

        val partQ = FileUtils.shellQuote(partitionPath)
        val destQ = FileUtils.shellQuote(destPath)
        val hashQ = FileUtils.shellQuote(hashPath)

        onLog("Membuat backup partisi $partitionPath -> $destPath ...")
        val ddRes = Shell.cmd(
            "(dd if=$partQ of=$destQ bs=4M || dd if=$partQ of=$destQ) && sync",
            "chmod 600 $destQ",
            "chown root:root $destQ || true"
        ).exec()

        if (!ddRes.isSuccess) {
            Shell.cmd("rm -f $destQ $hashQ").exec()
            val err = ddRes.err.joinToString("\n").ifBlank { "dd gagal saat membuat backup." }
            return Result.failure(IllegalStateException(err))
        }

        onLog("Memverifikasi SHA-256 backup terhadap partisi...")
        val verifyRes = Shell.cmd(
            "P_HASH=\$(dd if=$partQ bs=4M 2>/dev/null | sha256sum | awk '{print \$1}')",
            "B_HASH=\$(sha256sum $destQ | awk '{print \$1}')",
            "SZ=\$(wc -c < $destQ | tr -d ' ')",
            "MT=\$(stat -c '%Y' $destQ 2>/dev/null || date +%s)",
            "printf '%s|%s|%s|%s\n' \"\$P_HASH\" \"\$B_HASH\" \"\$SZ\" \"\$MT\""
        ).exec()

        val line = verifyRes.out.lastOrNull()?.split('|')
        val partHash = line?.getOrNull(0)?.trim()?.lowercase(Locale.US).orEmpty()
        val backupHash = line?.getOrNull(1)?.trim()?.lowercase(Locale.US).orEmpty()
        val sizeBytes = line?.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        val mtime = line?.getOrNull(3)?.trim()?.toLongOrNull() ?: (System.currentTimeMillis() / 1000L)

        if (partHash.length != 64 || backupHash.length != 64 || partHash != backupHash) {
            Shell.cmd("rm -f $destQ $hashQ").exec()
            return Result.failure(
                IllegalStateException(
                    "Verifikasi SHA-256 backup gagal! Partisi=$partHash, Backup=$backupHash"
                )
            )
        }

        val writeHashRes = Shell.cmd(
            "printf '%s  %s\\n' ${FileUtils.shellQuote(backupHash)} ${FileUtils.shellQuote(fileName)} > $hashQ",
            "chmod 600 $hashQ",
            "chown root:root $hashQ || true"
        ).exec()

        if (!writeHashRes.isSuccess) {
            Shell.cmd("rm -f $destQ $hashQ").exec()
            return Result.failure(IllegalStateException("Gagal menyimpan file .sha256 backup."))
        }

        onLog("Backup terverifikasi OK ($fileName, ${FileUtils.formatBytes(sizeBytes)}, SHA-256: ${backupHash.take(12)}...)")
        return Result.success(
            BackupEntry(
                fileName = fileName,
                fullPath = destPath,
                sizeBytes = sizeBytes,
                modifiedEpochSec = mtime,
                slotLabel = cleanSlot,
                expectedSha256 = backupHash,
                actualSha256 = backupHash,
                hashStatus = BackupHashStatus.MATCH
            )
        )
    }

    fun importBackupFromCacheFile(cacheImgFile: File, sha256: String): Result<BackupEntry> {
        ensureBackupDir().onFailure { return Result.failure(it) }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "logo_backup_import_$ts.img"
        val destPath = "$BACKUP_DIR/$fileName"
        val hashPath = "$destPath.sha256"

        val srcQ = FileUtils.shellQuote(cacheImgFile.absolutePath)
        val destQ = FileUtils.shellQuote(destPath)
        val hashQ = FileUtils.shellQuote(hashPath)

        val res = Shell.cmd(
            "cp -f $srcQ $destQ",
            "chmod 600 $destQ",
            "chown root:root $destQ || true",
            "printf '%s  %s\\n' ${FileUtils.shellQuote(sha256)} ${FileUtils.shellQuote(fileName)} > $hashQ",
            "chmod 600 $hashQ",
            "chown root:root $hashQ || true"
        ).exec()

        if (!res.isSuccess) {
            Shell.cmd("rm -f $destQ $hashQ").exec()
            return Result.failure(IllegalStateException("Gagal mengimpor backup ke $BACKUP_DIR"))
        }

        return Result.success(
            BackupEntry(
                fileName = fileName,
                fullPath = destPath,
                sizeBytes = cacheImgFile.length(),
                modifiedEpochSec = System.currentTimeMillis() / 1000L,
                slotLabel = "import",
                expectedSha256 = sha256,
                actualSha256 = sha256,
                hashStatus = BackupHashStatus.MATCH
            )
        )
    }

    fun copyBackupToCache(backup: BackupEntry, destFile: File): Result<File> {
        destFile.parentFile?.mkdirs()
        val uid = Process.myUid()
        val srcQ = FileUtils.shellQuote(backup.fullPath)
        val dstQ = FileUtils.shellQuote(destFile.absolutePath)
        val res = Shell.cmd(
            "cp -f $srcQ $dstQ",
            "chmod 644 $dstQ",
            "chown $uid:$uid $dstQ || true"
        ).exec()
        return if (res.isSuccess && destFile.exists() && destFile.length() > 0L) {
            Result.success(destFile)
        } else {
            Result.failure(IllegalStateException("Gagal menyalin backup ${backup.fileName} ke cache aplikasi."))
        }
    }

    fun deleteBackup(backup: BackupEntry): Result<Unit> {
        val imgQ = FileUtils.shellQuote(backup.fullPath)
        val shaQ = FileUtils.shellQuote("${backup.fullPath}.sha256")
        val res = Shell.cmd("rm -f $imgQ $shaQ").exec()
        return if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Gagal menghapus backup ${backup.fileName}"))
        }
    }

    private fun parseSlotFromFileName(fileName: String): String {
        val m = Regex("^logo_backup_([^_]+)_").find(fileName)
        return when (val s = m?.groupValues?.getOrNull(1)?.lowercase(Locale.US)) {
            "a" -> "Slot A"
            "b" -> "Slot B"
            "nonab" -> "Non-A/B"
            "import" -> "Impor"
            null -> "-"
            else -> s
        }
    }
}

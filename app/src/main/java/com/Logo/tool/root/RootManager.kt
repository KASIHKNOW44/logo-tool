package com.Logo.tool.root

import android.content.Context
import android.os.Process
import com.Logo.tool.BuildConfig
import com.Logo.tool.util.FileUtils
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RootManager(private val context: Context) {

    val backupStore = BackupStore(context)

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(30)
        )
    }

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            false
        }
    }

    fun detectPartitionInfo(onLog: (String) -> Unit): Result<PartitionInfo> {
        if (!isRootAvailable()) {
            return Result.failure(IllegalStateException("Akses root (su) tidak diberikan atau perangkat belum di-root."))
        }

        val slotRes = Shell.cmd(
            "getprop ro.boot.slot_suffix",
            "getprop ro.product.model"
        ).exec()
        val activeSlot = slotRes.out.getOrNull(0)?.trim().orEmpty()
        val model = slotRes.out.getOrNull(1)?.trim().orEmpty().ifBlank { "device" }

        val candidatesScript = """
            SLOT="${'$'}(getprop ro.boot.slot_suffix)"
            FOUND=""
            for C in \
              "/dev/block/by-name/logo${'$'}SLOT" \
              "/dev/block/by-name/logo" \
              "/dev/block/bootdevice/by-name/logo" \
              /dev/block/platform/*/by-name/logo
            do
              if [ -e "${'$'}C" ]; then
                FOUND="${'$'}C"
                break
              fi
            done
            if [ -z "${'$'}FOUND" ]; then
              echo "ERR_NOT_FOUND"
              exit 1
            fi
            REAL=${'$'}(readlink -f "${'$'}FOUND" 2>/dev/null || echo "${'$'}FOUND")
            SZ=${'$'}(blockdev --getsize64 "${'$'}REAL" 2>/dev/null || blockdev --getsize64 "${'$'}FOUND" 2>/dev/null || echo 0)
            echo "MAIN|${'$'}FOUND|${'$'}REAL|${'$'}SZ"
            for S in a b; do
              SP=""
              for CAND in "/dev/block/by-name/logo_${'$'}S" "/dev/block/bootdevice/by-name/logo_${'$'}S" /dev/block/platform/*/by-name/logo_${'$'}S; do
                if [ -e "${'$'}CAND" ]; then SP="${'$'}CAND"; break; fi
              done
              if [ -n "${'$'}SP" ]; then
                SR=${'$'}(readlink -f "${'$'}SP" 2>/dev/null || echo "${'$'}SP")
                SSZ=${'$'}(blockdev --getsize64 "${'$'}SR" 2>/dev/null || blockdev --getsize64 "${'$'}SP" 2>/dev/null || echo 0)
                echo "SLOT_${'$'}S|${'$'}SP|${'$'}SR|${'$'}SSZ"
              fi
            done
        """.trimIndent()

        val res = Shell.cmd(candidatesScript).exec()
        if (!res.isSuccess || res.out.any { it.trim() == "ERR_NOT_FOUND" }) {
            return Result.failure(IllegalStateException("Partisi logo tidak ditemukan di perangkat ini."))
        }

        var mainPath = ""
        var mainReal = ""
        var mainSize = 0L
        var slotAPath: String? = null
        var slotAReal: String? = null
        var slotASize = 0L
        var slotBPath: String? = null
        var slotBReal: String? = null
        var slotBSize = 0L

        for (line in res.out) {
            val cols = line.split('|')
            if (cols.size < 4) continue
            when (cols[0]) {
                "MAIN" -> {
                    mainPath = cols[1].trim()
                    mainReal = cols[2].trim()
                    mainSize = cols[3].trim().toLongOrNull() ?: 0L
                }
                "SLOT_a" -> {
                    slotAPath = cols[1].trim()
                    slotAReal = cols[2].trim()
                    slotASize = cols[3].trim().toLongOrNull() ?: 0L
                }
                "SLOT_b" -> {
                    slotBPath = cols[1].trim()
                    slotBReal = cols[2].trim()
                    slotBSize = cols[3].trim().toLongOrNull() ?: 0L
                }
            }
        }

        if (mainPath.isBlank() || !FileUtils.isWhitelistedBlockDevice(mainPath, mainReal)) {
            return Result.failure(SecurityException("Path partisi tidak memenuhi whitelist logo/logo_a/logo_b: $mainPath"))
        }

        val isAb = activeSlot.isNotBlank() || (slotAPath != null && slotBPath != null)
        val info = PartitionInfo(
            detectedPath = mainPath,
            resolvedPath = mainReal,
            sizeBytes = mainSize,
            activeSlot = activeSlot,
            isAbDevice = isAb,
            slotAPath = slotAPath,
            slotAResolved = slotAReal,
            slotASize = slotASize,
            slotBPath = slotBPath,
            slotBResolved = slotBReal,
            slotBSize = slotBSize,
            deviceModel = model
        )
        onLog("Partisi terdeteksi: ${info.detectedPath} -> ${info.resolvedPath} (${FileUtils.formatBytes(info.sizeBytes)}, ${info.activeSlotLabel})")
        return Result.success(info)
    }

    fun resolveTargetsForSlot(info: PartitionInfo, slotTarget: SlotTarget): Result<List<Pair<String, String>>> {
        val targets = mutableListOf<Pair<String, String>>()
        when (slotTarget) {
            SlotTarget.ACTIVE -> {
                val label = when (info.activeSlot) {
                    "_a" -> "a"
                    "_b" -> "b"
                    else -> "nonab"
                }
                targets += label to info.detectedPath
            }
            SlotTarget.SLOT_A -> {
                val p = info.slotAPath ?: return Result.failure(IllegalStateException("Partisi logo_a tidak ditemukan."))
                targets += "a" to p
            }
            SlotTarget.SLOT_B -> {
                val p = info.slotBPath ?: return Result.failure(IllegalStateException("Partisi logo_b tidak ditemukan."))
                targets += "b" to p
            }
            SlotTarget.BOTH -> {
                val pa = info.slotAPath
                val pb = info.slotBPath
                if (pa == null || pb == null) {
                    return Result.failure(IllegalStateException("Perangkat bukan A/B atau slot a/b tidak lengkap."))
                }
                targets += "a" to pa
                targets += "b" to pb
            }
        }

        for ((_, path) in targets) {
            if (!FileUtils.isWhitelistedPartitionName(path)) {
                return Result.failure(SecurityException("Partisi ditolak oleh whitelist keamanan: $path"))
            }
        }
        return Result.success(targets)
    }

    /**
     * Dumps logo partition into app cacheDir for SAF saving or direct opening in Editor.
     */
    fun dumpLogoToCache(
        info: PartitionInfo,
        slotTarget: SlotTarget,
        trimTrailingFf: Boolean,
        onLog: (String) -> Unit
    ): Result<DumpResult> {
        val targets = resolveTargetsForSlot(info, slotTarget).getOrElse { return Result.failure(it) }
        val (slotLabel, partPath) = targets.first()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val modelSlug = FileUtils.sanitizeModel(info.deviceModel)
        val fileName = "logo_dump_${modelSlug}_$ts.img"

        val dumpCacheDir = File(context.cacheDir, "dumps").apply { mkdirs() }
        val cacheFile = File(dumpCacheDir, fileName)
        val uid = Process.myUid()

        onLog("Menarik logo dari $partPath...")
        val res = Shell.cmd(
            "(dd if=${FileUtils.shellQuote(partPath)} of=${FileUtils.shellQuote(cacheFile.absolutePath)} bs=4M || dd if=${FileUtils.shellQuote(partPath)} of=${FileUtils.shellQuote(cacheFile.absolutePath)})",
            "chmod 644 ${FileUtils.shellQuote(cacheFile.absolutePath)}",
            "chown $uid:$uid ${FileUtils.shellQuote(cacheFile.absolutePath)} || true"
        ).exec()

        if (!res.isSuccess || !cacheFile.exists() || cacheFile.length() == 0L) {
            val err = res.err.joinToString("\n").ifBlank { "Gagal melakukan dd dump dari $partPath" }
            return Result.failure(IllegalStateException(err))
        }

        if (trimTrailingFf) {
            val before = cacheFile.length()
            val after = FileUtils.trimMtkTrailingPadding(cacheFile)
            onLog("Trim padding 0xFF: ${FileUtils.formatBytes(before)} -> ${FileUtils.formatBytes(after)}")
        }

        val sha = FileUtils.sha256(cacheFile)
        onLog("Dump siap (${FileUtils.formatBytes(cacheFile.length())}, SHA-256: $sha)")
        return Result.success(
            DumpResult(
                cacheFile = cacheFile,
                suggestedName = fileName,
                sizeBytes = cacheFile.length(),
                sha256 = sha,
                slotLabel = slotLabel
            )
        )
    }

    fun flashLogo(
        info: PartitionInfo,
        sourceFile: File,
        slotTarget: SlotTarget,
        mode: FlashMode,
        onLog: (String) -> Unit
    ): Result<FlashResult> {
        val targets = resolveTargetsForSlot(info, slotTarget).getOrElse { return Result.failure(it) }

        val maxAllowedSize = targets.minOfOrNull { (label, _) ->
            when (label) {
                "a" -> if (info.slotASize > 0) info.slotASize else info.sizeBytes
                "b" -> if (info.slotBSize > 0) info.slotBSize else info.sizeBytes
                else -> info.sizeBytes
            }
        }?.takeIf { it > 0L }

        val validation = FileUtils.validateMtkLogoFile(sourceFile, maxAllowedSize)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errorReason ?: "File tidak valid."))
        }
        val sourceSize = validation.sizeBytes
        val sourceSha = validation.sha256

        val createdBackups = mutableListOf<BackupEntry>()
        if (mode == FlashMode.BACKUP_AND_FLASH) {
            for ((slotLabel, partPath) in targets) {
                val bkRes = backupStore.createBackupFromPartition(partPath, slotLabel, onLog)
                if (bkRes.isFailure) {
                    val reason = bkRes.exceptionOrNull()?.message ?: "Backup gagal."
                    onLog("DIBATALKAN: $reason")
                    return Result.failure(IllegalStateException("Flash dibatalkan karena backup gagal: $reason"))
                }
                createdBackups += bkRes.getOrThrow()
            }
        } else {
            onLog("PERINGATAN: Mode Flash Langsung aktif (tanpa backup).")
        }

        val uid = Process.myUid()
        val outcomes = mutableListOf<FlashSlotOutcome>()
        val verifyTmpDir = File(context.cacheDir, "verify").apply { mkdirs() }

        for ((slotLabel, partPath) in targets) {
            if (!FileUtils.isWhitelistedPartitionName(partPath)) {
                return Result.failure(SecurityException("Penulisan ke partisi $partPath ditolak whitelist."))
            }
            onLog("Menulis ${sourceFile.name} (${FileUtils.formatBytes(sourceSize)}) ke $partPath...")
            val ddRes = Shell.cmd(
                "(dd if=${FileUtils.shellQuote(sourceFile.absolutePath)} of=${FileUtils.shellQuote(partPath)} bs=4M conv=fsync || dd if=${FileUtils.shellQuote(sourceFile.absolutePath)} of=${FileUtils.shellQuote(partPath)} conv=fsync) && sync"
            ).exec()

            if (!ddRes.isSuccess) {
                val err = ddRes.err.joinToString("\n").ifBlank { "dd gagal menulis ke $partPath" }
                onLog("GAGAL menulis ke $partPath: $err")
                return Result.success(
                    FlashResult(
                        allVerified = false,
                        outcomes = outcomes,
                        createdBackups = createdBackups,
                        errorMessage = err
                    )
                )
            }

            onLog("Memverifikasi hasil tulis pada $partPath (read-back SHA-256)...")
            val readbackFile = File(verifyTmpDir, "readback_${slotLabel}.img")
            val blockCount = (sourceSize + 4095L) / 4096L
            val rbRes = Shell.cmd(
                "dd if=${FileUtils.shellQuote(partPath)} of=${FileUtils.shellQuote(readbackFile.absolutePath)} bs=4096 count=$blockCount",
                "chmod 644 ${FileUtils.shellQuote(readbackFile.absolutePath)}",
                "chown $uid:$uid ${FileUtils.shellQuote(readbackFile.absolutePath)} || true"
            ).exec()

            val readbackSha = if (rbRes.isSuccess && readbackFile.exists() && readbackFile.length() >= sourceSize) {
                RandomAccessFile(readbackFile, "rw").use { it.setLength(sourceSize) }
                val hash = FileUtils.sha256(readbackFile)
                readbackFile.delete()
                hash
            } else {
                ""
            }

            val ok = readbackSha.isNotEmpty() && readbackSha == sourceSha
            onLog(
                if (ok) "Verifikasi $partPath: LULUS (SHA-256 cocok: ${readbackSha.take(12)}...)"
                else "Verifikasi $partPath: GAGAL! Sumber=${sourceSha.take(12)}..., Partisi=${readbackSha.take(12).ifEmpty { "kosong" }}"
            )
            outcomes += FlashSlotOutcome(
                slotName = slotLabel,
                partitionPath = partPath,
                verified = ok,
                sourceSha256 = sourceSha,
                readbackSha256 = readbackSha
            )
        }

        val allOk = outcomes.isNotEmpty() && outcomes.all { it.verified }
        return Result.success(
            FlashResult(
                allVerified = allOk,
                outcomes = outcomes,
                createdBackups = createdBackups
            )
        )
    }

    fun restoreBackup(
        info: PartitionInfo,
        backup: BackupEntry,
        slotTarget: SlotTarget,
        onLog: (String) -> Unit
    ): Result<FlashResult> {
        val restoreCache = File(context.cacheDir, "restore_tmp_${backup.fileName}")
        val copied = backupStore.copyBackupToCache(backup, restoreCache).getOrElse {
            return Result.failure(it)
        }

        try {
            if (backup.expectedSha256 != null) {
                val actual = FileUtils.sha256(copied)
                if (!actual.equals(backup.expectedSha256, ignoreCase = true)) {
                    return Result.failure(
                        IllegalStateException(
                            "Hash backup tidak cocok dengan .sha256! Restore dibatalkan demi keamanan."
                        )
                    )
                }
            }
            return flashLogo(
                info = info,
                sourceFile = copied,
                slotTarget = slotTarget,
                mode = FlashMode.DIRECT_FLASH,
                onLog = onLog
            )
        } finally {
            copied.delete()
        }
    }
}

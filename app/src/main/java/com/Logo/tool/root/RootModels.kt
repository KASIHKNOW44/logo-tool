package com.Logo.tool.root

import java.io.File

enum class FlashMode {
    BACKUP_AND_FLASH,
    DIRECT_FLASH
}

enum class SlotTarget {
    ACTIVE,
    SLOT_A,
    SLOT_B,
    BOTH
}

data class PartitionInfo(
    val detectedPath: String,
    val resolvedPath: String,
    val sizeBytes: Long,
    val activeSlot: String, // "_a", "_b", or ""
    val isAbDevice: Boolean,
    val slotAPath: String?,
    val slotAResolved: String?,
    val slotASize: Long,
    val slotBPath: String?,
    val slotBResolved: String?,
    val slotBSize: Long,
    val deviceModel: String
) {
    val activeSlotLabel: String
        get() = when {
            !isAbDevice -> "non-A/B"
            activeSlot == "_a" -> "a (_a)"
            activeSlot == "_b" -> "b (_b)"
            else -> activeSlot.ifBlank { "aktif" }
        }
}

enum class BackupHashStatus {
    MATCH,
    MISMATCH,
    NO_HASH_FILE
}

data class BackupEntry(
    val fileName: String,
    val fullPath: String,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val slotLabel: String,
    val expectedSha256: String?,
    val actualSha256: String?,
    val hashStatus: BackupHashStatus
)

data class DumpResult(
    val cacheFile: File,
    val suggestedName: String,
    val sizeBytes: Long,
    val sha256: String,
    val slotLabel: String
)

data class FlashSlotOutcome(
    val slotName: String,
    val partitionPath: String,
    val verified: Boolean,
    val sourceSha256: String,
    val readbackSha256: String
)

data class FlashResult(
    val allVerified: Boolean,
    val outcomes: List<FlashSlotOutcome>,
    val createdBackups: List<BackupEntry>,
    val errorMessage: String? = null
)

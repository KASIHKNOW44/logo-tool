package com.Logo.tool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.Logo.tool.root.BackupEntry
import com.Logo.tool.root.BackupHashStatus
import com.Logo.tool.root.FlashMode
import com.Logo.tool.root.SlotTarget
import com.Logo.tool.ui.theme.JetBrainsMonoFontFamily
import com.Logo.tool.ui.theme.LocalLogoColors
import com.Logo.tool.util.FileUtils
import com.Logo.tool.viewmodel.MainUiState
import com.Logo.tool.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RootToolScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    copy: UiCopy,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val c = LocalLogoColors.current
    val context = LocalContext.current
    val canRunRootActions = state.isRootAvailable && state.partitionInfo != null && !state.isBusy

    var showFlashConfirmDialog by remember { mutableStateOf(false) }
    var pendingDeleteBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var pendingRestoreBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var restoreSlotSelection by remember { mutableStateOf(SlotTarget.ACTIVE) }

    val pickFlashImgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectFlashFileFromUri(it) }
    }

    val pickImportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBackupFromUri(it) }
    }

    val logScrollState = rememberScrollState()
    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = c.accent,
                    trackColor = c.chip
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .align(Alignment.CenterHorizontally)
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Status Root
                item(key = "card_root_status") {
                    SectionCard(
                        title = copy.rootStatusTitle,
                        icon = Icons.Filled.Security,
                        headerAction = {
                            OutlinedButton(
                                onClick = { viewModel.refreshRootEnvironment() },
                                enabled = !state.isBusy,
                                modifier = Modifier.testTag("root_recheck_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(copy.recheckBtn)
                            }
                        }
                    ) {
                        if (state.isRootAvailable) {
                            StatusBannerBox(
                                text = copy.rootGranted,
                                color = c.ok,
                                icon = Icons.Filled.CheckCircle
                            )
                        } else {
                            StatusBannerBox(
                                text = "${copy.rootDenied}\n${copy.rootDeniedHint}",
                                color = c.err,
                                icon = Icons.Filled.Error
                            )
                        }
                    }
                }

                // 2. Info Partisi
                item(key = "card_partition_info") {
                    SectionCard(
                        title = copy.partInfoTitle,
                        icon = Icons.Filled.Storage
                    ) {
                        val info = state.partitionInfo
                        if (info == null) {
                            StatusBannerBox(
                                text = state.partitionError ?: copy.partNotFound,
                                color = c.warn,
                                icon = Icons.Filled.Warning
                            )
                        } else {
                            InfoKeyValueRow(copy.labelDetectedPath, info.detectedPath)
                            InfoKeyValueRow(copy.labelRealPath, info.resolvedPath)
                            InfoKeyValueRow(
                                copy.labelPartSize,
                                "${FileUtils.formatBytes(info.sizeBytes)} (${info.sizeBytes} bytes)"
                            )
                            InfoKeyValueRow(copy.labelActiveSlot, info.activeSlotLabel, monoValue = false)
                            InfoKeyValueRow(
                                copy.labelDeviceType,
                                if (info.isAbDevice) {
                                    "A/B (${info.slotAPath ?: "-"} | ${info.slotBPath ?: "-"})"
                                } else {
                                    "Non-A/B"
                                }
                            )
                        }
                    }
                }

                // 3. Dump
                item(key = "card_dump") {
                    SectionCard(
                        title = copy.dumpTitle,
                        icon = Icons.Filled.Download,
                        subtitle = copy.dumpSubtitle
                    ) {
                        val isAb = state.partitionInfo?.isAbDevice == true
                        if (isAb) {
                            Text(copy.slotLabel, style = MaterialTheme.typography.labelMedium, color = c.mute)
                            SegmentedChoiceRow(
                                options = listOf(
                                    copy.slotActive to (state.dumpSlotTarget == SlotTarget.ACTIVE),
                                    copy.slotA to (state.dumpSlotTarget == SlotTarget.SLOT_A),
                                    copy.slotB to (state.dumpSlotTarget == SlotTarget.SLOT_B)
                                ),
                                enabled = canRunRootActions,
                                onSelect = { idx ->
                                    val target = when (idx) {
                                        1 -> SlotTarget.SLOT_A
                                        2 -> SlotTarget.SLOT_B
                                        else -> SlotTarget.ACTIVE
                                    }
                                    viewModel.setDumpSlotTarget(target)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.soft, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(copy.trimFfLabel, style = MaterialTheme.typography.labelLarge, color = c.ink)
                                Text(copy.trimFfHint, style = MaterialTheme.typography.bodySmall, color = c.mute)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = state.trimTrailingFf,
                                onCheckedChange = { viewModel.setTrimTrailingFf(it) },
                                enabled = !state.isBusy
                            )
                        }

                        Button(
                            onClick = { viewModel.dumpLogo(openInEditorAfter = false) },
                            enabled = canRunRootActions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dump_logo_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(copy.dumpActionBtn)
                        }

                        state.lastDumpResult?.let { dump ->
                            StatusBannerBox(
                                text = "${dump.suggestedName}\n${FileUtils.formatBytes(dump.sizeBytes)} • SHA-256: ${dump.sha256}",
                                color = c.ok,
                                icon = Icons.Filled.CheckCircle
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.openLastDumpInEditor() },
                                    enabled = !state.isBusy,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("open_dump_in_editor_button")
                                ) {
                                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(copy.openInEditorBtn)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.saveLastDumpViaSaf() },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(copy.saveSafBtn)
                                }
                            }
                        }
                    }
                }

                // 4. Flash ke Partisi Logo (index 3)
                item(key = "card_flash") {
                    SectionCard(
                        title = copy.flashTitle,
                        icon = Icons.Filled.FlashOn
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { pickFlashImgLauncher.launch(arrayOf("*/*")) },
                                enabled = !state.isBusy,
                                modifier = Modifier.testTag("pick_flash_img_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(copy.pickImgBtn)
                            }
                            OutlinedButton(
                                onClick = { viewModel.useLastEditedForFlash() },
                                enabled = !state.isBusy && state.lastEditedLogoFile != null,
                                modifier = Modifier.testTag("use_last_edited_button")
                            ) {
                                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(copy.useLastEditedBtn)
                            }
                            if (state.pendingFlashFile != null) {
                                OutlinedButton(
                                    onClick = { viewModel.clearPendingFlashFile() },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.testTag("clear_flash_file_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.mute)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(copy.clearFileBtn)
                                }
                            }
                        }

                        val sel = state.pendingFlashFile
                        val targetLabel = state.partitionInfo?.let { info ->
                            if (info.isAbDevice && state.flashBothSlots) {
                                "${info.slotAPath} & ${info.slotBPath}"
                            } else {
                                info.detectedPath
                            }
                        } ?: "-"

                        if (sel == null) {
                            Text(copy.noFileSelected, style = MaterialTheme.typography.bodySmall, color = c.mute)
                        } else if (sel.validation.isValid) {
                            StatusBannerBox(
                                text = "${sel.displayName} (${FileUtils.formatBytes(sel.validation.sizeBytes)})\n" +
                                    "${copy.targetPartLabel}: $targetLabel\n" +
                                    "MTK Header OK (88 16 88 58 / logo)\n" +
                                    "SHA-256: ${sel.validation.sha256}",
                                color = c.ok,
                                icon = Icons.Filled.CheckCircle
                            )
                        } else {
                            StatusBannerBox(
                                text = "${sel.displayName}\n${sel.validation.errorReason ?: "File tidak valid."}",
                                color = c.err,
                                icon = Icons.Filled.Error
                            )
                        }

                        SegmentedChoiceRow(
                            options = listOf(
                                copy.modeBackupFlash to (state.flashMode == FlashMode.BACKUP_AND_FLASH),
                                copy.modeDirectFlash to (state.flashMode == FlashMode.DIRECT_FLASH)
                            ),
                            enabled = !state.isBusy,
                            onSelect = { idx ->
                                viewModel.setFlashMode(
                                    if (idx == 0) FlashMode.BACKUP_AND_FLASH else FlashMode.DIRECT_FLASH
                                )
                            }
                        )

                        if (state.flashMode == FlashMode.DIRECT_FLASH) {
                            StatusBannerBox(
                                text = copy.directFlashWarn,
                                color = c.err,
                                icon = Icons.Filled.Warning
                            )
                        }

                        if (state.partitionInfo?.isAbDevice == true) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = state.flashBothSlots,
                                    onCheckedChange = { viewModel.setFlashBothSlots(it) },
                                    enabled = !state.isBusy
                                )
                                Text(
                                    text = copy.flashBothSlotsLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.ink
                                )
                            }
                        }

                        Button(
                            onClick = { showFlashConfirmDialog = true },
                            enabled = canRunRootActions && sel?.validation?.isValid == true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("flash_logo_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.flashMode == FlashMode.DIRECT_FLASH) c.err else c.accent,
                                contentColor = c.onAccent
                            )
                        ) {
                            Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(copy.flashActionBtn)
                        }

                        state.lastFlashResult?.let { flashRes ->
                            val bannerColor = if (flashRes.allVerified) c.ok else c.err
                            val detailLines = buildString {
                                appendLine(if (flashRes.allVerified) copy.verifyPassed else copy.verifyFailed)
                                flashRes.outcomes.forEach { o ->
                                    val status = if (o.verified) "LULUS" else "GAGAL"
                                    appendLine("• ${o.partitionPath} [$status]: ${o.readbackSha256.take(16)}...")
                                }
                                if (!flashRes.errorMessage.isNullOrBlank()) {
                                    appendLine(flashRes.errorMessage)
                                }
                            }.trim()
                            StatusBannerBox(
                                text = detailLines,
                                color = bannerColor,
                                icon = if (flashRes.allVerified) Icons.Filled.CheckCircle else Icons.Filled.Error
                            )

                            if (!flashRes.allVerified && flashRes.createdBackups.isNotEmpty()) {
                                Button(
                                    onClick = { viewModel.restoreLatestCreatedBackup() },
                                    enabled = canRunRootActions,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = c.err, contentColor = c.onAccent)
                                ) {
                                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(copy.restoreEmergencyBtn)
                                }
                            }
                        }
                    }
                }

                // 5. Backup & Restore
                item(key = "card_backup_restore") {
                    SectionCard(
                        title = copy.backupTitle,
                        icon = Icons.Filled.Backup,
                        subtitle = copy.backupSubtitle,
                        headerAction = {
                            IconButton(
                                onClick = { viewModel.refreshBackups() },
                                enabled = state.isRootAvailable && !state.isBusy
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = copy.recheckBtn, tint = c.accent)
                            }
                        }
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.backupNow() },
                                enabled = canRunRootActions,
                                modifier = Modifier.testTag("backup_now_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)
                            ) {
                                Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(copy.backupNowBtn)
                            }
                            OutlinedButton(
                                onClick = { pickImportBackupLauncher.launch(arrayOf("*/*")) },
                                enabled = state.isRootAvailable && !state.isBusy,
                                modifier = Modifier.testTag("import_backup_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(copy.importBackupBtn)
                            }
                        }

                        if (!state.backupLoadError.isNullOrBlank()) {
                            StatusBannerBox(
                                text = state.backupLoadError,
                                color = c.warn,
                                icon = Icons.Filled.Warning
                            )
                        }

                        if (state.backups.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = c.soft,
                                border = BorderStroke(1.dp, c.line)
                            ) {
                                Text(
                                    text = copy.backupEmpty,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.mute,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(18.dp)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.backups.forEach { bk ->
                                    BackupItemRow(
                                        entry = bk,
                                        copy = copy,
                                        enabled = !state.isBusy,
                                        canRestore = canRunRootActions,
                                        onRestore = {
                                            restoreSlotSelection = SlotTarget.ACTIVE
                                            pendingRestoreBackup = bk
                                        },
                                        onOpenEditor = { viewModel.openBackupInEditor(bk) },
                                        onExport = { viewModel.exportBackupViaSaf(bk) },
                                        onShare = { viewModel.shareBackup(bk) },
                                        onDelete = { pendingDeleteBackup = bk }
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Log Terminal Live
                item(key = "card_logs") {
                    SectionCard(
                        title = copy.logTitle,
                        icon = Icons.Filled.Code,
                        headerAction = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(
                                            ClipData.newPlainText("LogoToolLogs", state.logs.joinToString("\n"))
                                        )
                                        viewModel.emitMessage(copy.msgCopiedLogs)
                                    }
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = copy.copyBtn, tint = c.accent)
                                }
                                IconButton(onClick = { viewModel.clearLogs() }) {
                                    Icon(Icons.Filled.Delete, contentDescription = copy.clearBtn, tint = c.mute)
                                }
                            }
                        }
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = c.soft,
                            border = BorderStroke(1.dp, c.line)
                        ) {
                            val hScroll = rememberScrollState()
                            Text(
                                text = if (state.logs.isEmpty()) "—" else state.logs.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = c.ink,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(logScrollState)
                                    .horizontalScroll(hScroll)
                            )
                        }
                    }
                }

                item(key = "footer") {
                    Text(
                        text = copy.footerCredit,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.mute,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Flash confirmation dialog
    if (showFlashConfirmDialog) {
        val sel = state.pendingFlashFile
        val info = state.partitionInfo
        if (sel != null && info != null) {
            val targetLabel = if (info.isAbDevice && state.flashBothSlots) {
                "${info.slotAPath} & ${info.slotBPath}"
            } else {
                info.detectedPath
            }
            AlertDialog(
                onDismissRequest = { showFlashConfirmDialog = false },
                title = { Text(copy.confirmFlashTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("File: ${sel.displayName}")
                        Text("Size: ${FileUtils.formatBytes(sel.validation.sizeBytes)}")
                        Text(
                            "SHA-256: ${sel.validation.sha256}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                        Text("${copy.targetPartLabel}: $targetLabel")
                        Text(
                            "Mode: ${if (state.flashMode == FlashMode.BACKUP_AND_FLASH) copy.modeBackupFlash else copy.modeDirectFlash}"
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.flashMode == FlashMode.DIRECT_FLASH) {
                                copy.directFlashWarn
                            } else {
                                if (state.language == "en") {
                                    "Ensure battery is sufficient and this file belongs to your device model."
                                } else {
                                    "Pastikan baterai cukup dan file berasal dari logo.img HP ini agar tidak bootloop."
                                }
                            },
                            color = if (state.flashMode == FlashMode.DIRECT_FLASH) c.err else c.warn,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showFlashConfirmDialog = false
                            viewModel.executeFlash()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.flashMode == FlashMode.DIRECT_FLASH) c.err else c.accent
                        )
                    ) {
                        Text(copy.confirmBtn)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFlashConfirmDialog = false }) {
                        Text(copy.cancelBtn)
                    }
                }
            )
        }
    }

    // Delete backup confirmation dialog
    pendingDeleteBackup?.let { bk ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBackup = null },
            title = { Text(copy.confirmDeleteTitle) },
            text = { Text("${bk.fileName}\n${bk.fullPath}") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteBackup = null
                        viewModel.deleteBackup(bk)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.err)
                ) {
                    Text(copy.deleteBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBackup = null }) {
                    Text(copy.cancelBtn)
                }
            }
        )
    }

    // Restore backup confirmation dialog
    pendingRestoreBackup?.let { bk ->
        val isAb = state.partitionInfo?.isAbDevice == true
        AlertDialog(
            onDismissRequest = { pendingRestoreBackup = null },
            title = { Text(copy.confirmRestoreTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${bk.fileName} (${FileUtils.formatBytes(bk.sizeBytes)})")
                    if (isAb) {
                        Text(copy.slotLabel, style = MaterialTheme.typography.labelMedium, color = c.mute)
                        SegmentedChoiceRow(
                            options = listOf(
                                copy.slotActive to (restoreSlotSelection == SlotTarget.ACTIVE),
                                copy.slotA to (restoreSlotSelection == SlotTarget.SLOT_A),
                                copy.slotB to (restoreSlotSelection == SlotTarget.SLOT_B),
                                copy.slotBoth to (restoreSlotSelection == SlotTarget.BOTH)
                            ),
                            enabled = true,
                            onSelect = { idx ->
                                restoreSlotSelection = when (idx) {
                                    1 -> SlotTarget.SLOT_A
                                    2 -> SlotTarget.SLOT_B
                                    3 -> SlotTarget.BOTH
                                    else -> SlotTarget.ACTIVE
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreBackup = null
                        viewModel.restoreBackup(bk, restoreSlotSelection)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent)
                ) {
                    Text(copy.restoreBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreBackup = null }) {
                    Text(copy.cancelBtn)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupItemRow(
    entry: BackupEntry,
    copy: UiCopy,
    enabled: Boolean,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onOpenEditor: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalLogoColors.current
    val dateStr = remember(entry.modifiedEpochSec) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(entry.modifiedEpochSec * 1000L))
    }
    val (hashText, hashColor) = when (entry.hashStatus) {
        BackupHashStatus.MATCH -> copy.hashMatch to c.ok
        BackupHashStatus.MISMATCH -> copy.hashMismatch to c.err
        BackupHashStatus.NO_HASH_FILE -> copy.hashMissing to c.warn
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = c.soft,
        border = BorderStroke(1.dp, c.line)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = c.ink
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(dateStr, c.mute)
                StatusPill(FileUtils.formatBytes(entry.sizeBytes), c.accent2)
                StatusPill(entry.slotLabel, c.accent)
                StatusPill(hashText, hashColor)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onRestore,
                    enabled = canRestore,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(copy.restoreBtn, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onOpenEditor,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(copy.openInEditorBtn, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onExport,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(copy.exportBtn, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onShare,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(copy.shareBtn, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.err)
                ) {
                    Text(copy.deleteBtn, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

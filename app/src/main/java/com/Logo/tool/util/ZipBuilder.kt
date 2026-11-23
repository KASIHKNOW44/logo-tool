package com.Logo.tool.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipBuilder {

    fun createFlashableLogoZip(
        logoFile: File,
        outZipFile: File,
        withBackup: Boolean
    ) {
        outZipFile.parentFile?.mkdirs()
        val updateBinary = buildUpdateBinaryScript(withBackup)
        val updaterScript = "#MAGISK\n".toByteArray(Charsets.UTF_8)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outZipFile))).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/com/google/android/updater-script"))
            zos.write(updaterScript)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/com/google/android/update-binary"))
            zos.write(updateBinary.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("logo.img"))
            val buf = ByteArray(65536)
            FileInputStream(logoFile).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    zos.write(buf, 0, n)
                }
            }
            zos.closeEntry()
        }
    }

    private fun buildUpdateBinaryScript(withBackup: Boolean): String {
        val backupBlock = if (withBackup) {
            """
            |BK_DIR="/data/adb/logo_tool"
            |TS=$(date +%Y%m%d_%H%M%S 2>/dev/null || echo "0")
            |if mkdir -p "${'$'}BK_DIR" 2>/dev/null && touch "${'$'}BK_DIR/.wtest" 2>/dev/null; then
            |  rm -f "${'$'}BK_DIR/.wtest"
            |  chmod 700 "${'$'}BK_DIR" 2>/dev/null
            |  BK_FILE="${'$'}BK_DIR/logo_backup_${'$'}TS.img"
            |  ui "- Membuat backup partisi saat ini..."
            |  if dd if="${'$'}P" of="${'$'}BK_FILE" bs=4M 2>/dev/null || dd if="${'$'}P" of="${'$'}BK_FILE" 2>/dev/null; then
            |    chmod 600 "${'$'}BK_FILE" 2>/dev/null
            |    if command -v sha256sum >/dev/null 2>&1; then
            |      sha256sum "${'$'}BK_FILE" | awk '{print ${'$'}1}' > "${'$'}BK_FILE.sha256"
            |    fi
            |    ui "- Backup tersimpan: ${'$'}BK_FILE"
            |  else
            |    ui "! Peringatan: backup gagal ditulis, melanjutkan flash..."
            |    rm -f "${'$'}BK_FILE"
            |  fi
            |else
            |  ui "! Peringatan: /data terenkripsi atau tidak dapat ditulis, backup dilewati."
            |fi
            """.trimMargin()
        } else {
            """
            |ui "- Mode tanpa backup (flash langsung)"
            """.trimMargin()
        }

        return """
            |#!/sbin/sh
            |OUTFD=/proc/self/fd/${'$'}2; ZIP="${'$'}3"
            |ui(){ echo "ui_print ${'$'}1" > ${'$'}OUTFD; echo "ui_print" > ${'$'}OUTFD; }
            |ui "Logo Tool - Flash logo.img"
            |rm -rf /tmp/lt && mkdir -p /tmp/lt
            |unzip -o "${'$'}ZIP" logo.img -d /tmp/lt >/dev/null 2>&1
            |IMG="/tmp/lt/logo.img"
            |if [ ! -s "${'$'}IMG" ]; then
            |  ui "! logo.img tidak ditemukan atau kosong"
            |  exit 1
            |fi
            |
            |# Validasi magic header MTK (88 16 88 58)
            |MAGIC=""
            |if command -v od >/dev/null 2>&1; then
            |  MAGIC=$(dd if="${'$'}IMG" bs=1 count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')
            |elif command -v hexdump >/dev/null 2>&1; then
            |  MAGIC=$(dd if="${'$'}IMG" bs=1 count=4 2>/dev/null | hexdump -ve '1/1 "%02x"')
            |fi
            |if [ -n "${'$'}MAGIC" ] && [ "${'$'}MAGIC" != "88168858" ]; then
            |  ui "! Header MTK tidak valid (${'$'}MAGIC != 88168858). Dibatalkan."
            |  rm -rf /tmp/lt
            |  exit 1
            |fi
            |
            |SLOT=$(getprop ro.boot.slot_suffix)
            |P=""
            |for CAND in \
            |  "/dev/block/by-name/logo${'$'}SLOT" \
            |  "/dev/block/by-name/logo" \
            |  "/dev/block/bootdevice/by-name/logo${'$'}SLOT" \
            |  "/dev/block/bootdevice/by-name/logo" \
            |  /dev/block/platform/*/by-name/logo${'$'}SLOT \
            |  /dev/block/platform/*/by-name/logo
            |do
            |  if [ -e "${'$'}CAND" ]; then
            |    P="${'$'}CAND"
            |    break
            |  fi
            |done
            |
            |if [ -z "${'$'}P" ]; then
            |  ui "! Partisi logo tidak ketemu"
            |  rm -rf /tmp/lt
            |  exit 1
            |fi
            |
            |IMG_SZ=$(wc -c < "${'$'}IMG" | tr -d ' ')
            |PART_SZ=$(blockdev --getsize64 "${'$'}P" 2>/dev/null || echo 0)
            |if [ "${'$'}PART_SZ" -gt 0 ] 2>/dev/null && [ "${'$'}IMG_SZ" -gt "${'$'}PART_SZ" ] 2>/dev/null; then
            |  ui "! Ukuran file (${'$'}IMG_SZ) melebihi partisi (${'$'}PART_SZ). Dibatalkan."
            |  rm -rf /tmp/lt
            |  exit 1
            |fi
            |
            |$backupBlock
            |
            |ui "- Menulis ke ${'$'}P (${'$'}IMG_SZ byte)..."
            |if ! (dd if="${'$'}IMG" of="${'$'}P" bs=4M conv=fsync 2>/dev/null || dd if="${'$'}IMG" of="${'$'}P" 2>/dev/null); then
            |  ui "! Gagal menulis partisi logo"
            |  rm -rf /tmp/lt
            |  exit 1
            |fi
            |sync
            |
            |if command -v cmp >/dev/null 2>&1; then
            |  if cmp -n "${'$'}IMG_SZ" "${'$'}IMG" "${'$'}P" >/dev/null 2>&1; then
            |    ui "- Verifikasi LULUS (cmp cocok)"
            |  else
            |    ui "! PERINGATAN: Verifikasi cmp gagal!"
            |    rm -rf /tmp/lt
            |    exit 1
            |  fi
            |fi
            |
            |rm -rf /tmp/lt
            |ui "OK: ${'$'}P"
            |exit 0
        """.trimMargin() + "\n"
    }
}

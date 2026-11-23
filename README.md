# Logo Tool

Aplikasi Android **100% offline** untuk mengedit `logo.img` MediaTek (MTK), membuat modul boot animation Magisk dari video, serta melakukan **dump, backup, flash, dan restore** partisi `logo` langsung di perangkat yang sudah di-root (**Magisk**, **KernelSU**, dan **SukiSU**).

> **Credit:** K4T, CLAUDE AI, Jester  
> **Lisensi:** MIT License

---

## Fitur Utama

### 1. Tab **Editor Logo** (Offline WebView)
Membungkus `assets/logotool.html` secara lokal dari `assets/` tanpa koneksi internet maupun CDN, dilengkapi jembatan native (`Android` & `AndroidRoot` via `assets/root.js`):
- **Edit `logo.img` MTK:** Baca header MTK (`0x58881688`), pratinjau galeri gambar, ganti boot logo atau gambar satuan (`cover` / `contain` / `stretch`), dan simpan `new_logo.img` atau `flash_logo.zip` langsung ke `/sdcard/Download/BootLogoTool/`.
- **Video → Boot Animation:** Ekstrak frame video menjadi `bootanimation.zip` dan kemas menjadi modul Magisk `bootanim_module.zip` (termasuk dukungan ganti sebagian part dari `bootanimation.zip` asli).
- **Muat dari HP (Root):** Tarik `logo.img` dari partisi HP lalu langsung buka di editor.
- **Flash hasil edit ke HP (Root):** Kirim hasil `new_logo.img` dari editor langsung ke tab Root Tool untuk divalidasi dan di-flash.

### 2. Tab **Root Tool** (Native Kotlin + Jetpack Compose + `libsu`)
- **A. Deteksi Root & Partisi:** Mengecek akses `su` dan mencari partisi logo secara berurutan (`/dev/block/by-name/logo$(getprop ro.boot.slot_suffix)`, `/dev/block/by-name/logo`, `/dev/block/bootdevice/by-name/logo`, `/dev/block/platform/*/by-name/logo`), menampilkan path asli (`readlink -f`), ukuran (`blockdev --getsize64`), slot aktif, dan status A/B.
- **B. Dump Partisi:** Menarik partisi logo ke `/sdcard/Download/BootLogoTool/logo_dump_<model>_<yyyyMMdd_HHmmss>.img` dengan opsi **Trim sisa 0xFF di akhir** (default ON), menampilkan SHA-256, dan tombol **Buka di editor**.
- **C. Flash Langsung dengan Validasi Ketat:**
  - Wajib lolos validasi: file tidak kosong, ukuran `<=` ukuran partisi, magic header `88 16 88 58` (`0x58881688`) dan nama `"logo"` di offset `0x08`, serta whitelist partisi (`logo`, `logo_a`, `logo_b`).
  - Dua mode: **Backup + Flash** (backup dulu + verifikasi SHA-256 sebelum menulis) dan **Flash langsung** (dengan peringatan merah).
  - Menulis dengan `dd conv=fsync && sync` lalu **verifikasi read-back SHA-256** (`LULUS` / `GAGAL`). Jika gagal dan ada backup dari langkah sebelumnya, tersedia tombol darurat **Restore backup tadi sekarang**.
- **C2. Backup & Restore (`/data/adb/logo_tool/`):** Daftar backup diurutkan terbaru, cek kecocokan `.sha256`, tombol **Backup sekarang**, **Impor .img**, **Restore** (slot aktif / a / b / keduanya), **Buka di editor**, **Ekspor** ke Download, **Bagikan**, dan **Hapus** (dengan konfirmasi).
- **D. Zip Flashable (TWRP / OrangeFox):** Membuat `/sdcard/Download/BootLogoTool/flash_logo_<timestamp>.zip` murni dengan `ZipOutputStream` (pilihan **dengan backup** atau **tanpa backup**, validasi magic header + `cmp`).
- **E. Ekspor Mentah:** Simpan `.img` dan bagikan via `FileProvider`.
- **F. Bootanimation dari HP:** Mencari `bootanimation.zip` di semua path ROM umum + fallback `find`, lalu langsung mengirimkannya ke tab Video → Boot Animation.

---

## Lokasi Penyimpanan Backup & Cara Mengambil Manual

Semua backup **hanya** disimpan di:
```text
/data/adb/logo_tool/
```
Folder ini dibuat lewat `su` dengan izin `chmod 700` dan owner `root:root`. Format file:
- `logo_backup_<slot|nonab>_<yyyyMMdd_HHmmss>.img`
- `logo_backup_<slot|nonab>_<yyyyMMdd_HHmmss>.img.sha256`

Backup **tidak pernah dihapus otomatis** oleh aplikasi.

### Cara mengambil backup secara manual:
1. **Lewat File Manager Root** (MT Manager / MiXplorer / Root Explorer): buka `/data/adb/logo_tool/` lalu salin file `.img` dan `.sha256`.
2. **Lewat `adb shell`:**
   ```sh
   adb shell
   su
   ls -la /data/adb/logo_tool/
   cp /data/adb/logo_tool/logo_backup_*.img /sdcard/Download/
   ```
   Lalu tarik dengan `adb pull /sdcard/Download/logo_backup_....img`.

---

## Peringatan Keamanan

- **Hanya untuk perangkat MediaTek** yang menggunakan format `logo.img` dengan magic `88 16 88 58` (`logo` di offset `0x08`).
- Mem-flash file yang ukurannya melebihi partisi atau bukan dari perangkat yang sesuai dapat menyebabkan bootloop atau layar hitam saat boot.
- Selalu gunakan mode **Backup + Flash** sebelum memodifikasi partisi logo, dan simpan salinan backup di luar HP (PC/cloud) untuk berjaga-jaga.

---

## Cara Build Lewat GitHub Actions (Tanpa Android Studio)

Repository ini sudah menyertakan `.github/workflows/build.yml`.

1. Upload seluruh project ini ke repository GitHub Anda (`main` atau `master`).
2. *(Opsional)* Jika ingin menandatangani release APK dengan keystore sendiri, tambahkan Secrets di **Settings → Secrets and variables → Actions**:
   - `KEYSTORE_BASE64` (hasil `base64 -w0 release.keystore`)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
   Jika secrets tidak diisi, workflow otomatis membuat keystore fallback agar build tetap berhasil.
3. Workflow berjalan otomatis setiap `push`, `pull_request`, atau bisa dijalankan manual lewat tab **Actions → Build Logo Tool APK → Run workflow**.
4. Setelah selesai, buka run workflow terbaru di tab **Actions** dan unduh artifact **`BootLogoTool-debug`** atau **`BootLogoTool-release`**.
5. Jika Anda mendorong tag versi (misalnya `git tag v1.0.0 && git push origin v1.0.0`), GitHub Release otomatis dibuat dengan kedua APK terlampir.

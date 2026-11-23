# Logo Tool

Aplikasi Android **100% offline** untuk mengedit `logo.img` MediaTek (MTK), membuat modul boot animation Magisk dari video, serta melakukan **dump, backup, flash, dan restore** partisi `logo` langsung di perangkat yang sudah di-root (**Magisk**, **KernelSU**, dan **SukiSU**).

> **Credit:** K4T, CLAUDE AI, Jester  
> **Lisensi:** MIT License

---

## Fitur Utama

### 1. Tab **Editor Logo** (Offline WebView)
Membungkus `assets/logotool.html` secara lokal lewat `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/logotool.html`) tanpa koneksi internet maupun CDN, dilengkapi jembatan native (`AndroidRoot` via `assets/root.js`):
- **Edit `logo.img` MTK:** Baca header MTK (`0x58881688`), pratinjau galeri gambar, ganti boot logo atau gambar satuan (`cover` / `contain` / `stretch`), dan simpan `new_logo.img` atau `flash_logo.zip` lewat dialog **Storage Access Framework (SAF)** ke folder pilihan Anda.
- **Video → Boot Animation:** Ekstrak frame video menjadi `bootanimation.zip` (pipeline cepat hardware `MediaCodec` + paralel JPEG + fallback JS `requestVideoFrameCallback`/Worker Pool) dan kemas menjadi modul Magisk `bootanim_module.zip` berisi `customize.sh` (deteksi path otomatis) lalu simpan lewat SAF.
- **Muat dari HP (Root):** Tarik `logo.img` dari partisi HP lalu langsung buka di editor.
- **Flash hasil edit ke HP (Root):** Kirim hasil `new_logo.img` dari editor langsung ke tab Root Tool untuk divalidasi dan di-flash dengan konfirmasi.

### 2. Tab **Root Tool** (Native Kotlin + Jetpack Compose + `libsu`)
1. **Status Root:** Mengecek akses `su` (Magisk / KernelSU / SukiSU).
2. **Info Partisi Logo:** Mendeteksi partisi logo berurutan (`/dev/block/by-name/logo$(getprop ro.boot.slot_suffix)`, `/dev/block/by-name/logo`, `/dev/block/bootdevice/by-name/logo`, `/dev/block/platform/*/by-name/logo`), menampilkan path asli (`readlink -f`), ukuran (`blockdev --getsize64`), slot aktif, dan layout A/B.
3. **Dump Partisi Logo:** Menarik `logo.img` (`dd bs=4M`) dengan opsi **Trim sisa 0xFF di akhir** (default ON), menyimpan via **SAF**, menampilkan SHA-256, dan tombol **Buka di editor**.
4. **Flash ke Partisi Logo:**
   - Wajib lolos validasi: file tidak kosong, ukuran `<=` ukuran partisi, magic header `88 16 88 58` (`0x58881688`) dan nama `"logo"` di offset `0x08`, serta whitelist partisi (`logo`, `logo_a`, `logo_b`).
   - Pilihan mode **Backup + Flash** (backup terverifikasi SHA-256 ke `/data/adb/logo_tool/` sebelum menulis) atau **Flash langsung** (dengan peringatan merah).
   - Menulis dengan `dd conv=fsync && sync` lalu **verifikasi read-back SHA-256** (`LULUS` / `GAGAL`), serta tombol darurat **Restore backup tadi sekarang** bila gagal.
5. **Backup & Restore (`/data/adb/logo_tool/`):** Daftar backup diurutkan terbaru, cek kecocokan `.sha256`, tombol **Backup sekarang**, **Impor .img**, **Restore** (slot aktif / a / b / keduanya), **Buka di editor**, **Ekspor (SAF)**, **Bagikan** (`FileProvider`), dan **Hapus** (dengan konfirmasi).
6. **Log Terminal Live:** Log monospace real-time dengan tombol **Salin** dan **Hapus**.

---

## Lokasi Penyimpanan Backup & Cara Mengambil Manual

Semua backup otomatis/manual **hanya** disimpan di:
```text
/data/adb/logo_tool/
```
Folder ini dibuat lewat `su` dengan izin `chmod 700` dan owner `root:root`. Format file:
- `logo_backup_<slot|nonab>_<yyyyMMdd_HHmmss>.img`
- `logo_backup_<slot|nonab>_<yyyyMMdd_HHmmss>.img.sha256`

Backup **tidak pernah dihapus otomatis** oleh aplikasi.

### Cara mengambil backup secara manual:
1. **Lewat aplikasi:** Tekan **Ekspor (SAF)** pada kartu backup untuk menyimpannya ke folder pilihan Anda.
2. **Lewat File Manager Root** (MT Manager / MiXplorer / Root Explorer): buka `/data/adb/logo_tool/`.
3. **Lewat `adb shell`:**
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
- Selalu gunakan mode **Backup + Flash** sebelum memodifikasi partisi logo.

---

## Cara Build Lewat GitHub Actions (Tanpa Android Studio)

Repository ini sudah menyertakan `.github/workflows/build.yml`.

1. Push project ini ke repository GitHub Anda (`main` atau `master`).
2. *(Opsional)* Untuk menandatangani release APK dengan keystore sendiri, tambahkan Secrets di **Settings → Secrets and variables → Actions**:
   - `KEYSTORE_BASE64` (hasil `base64 -w0 release.keystore`)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
3. Buka tab **Actions** → **Build Logo Tool APK** dan unduh artifact **`BootLogoTool-debug`** atau **`BootLogoTool-release`**.

# Verifikasi What If

## Verifikasi awal versi 1.0

Uji dilakukan dengan JDK 21.0.13 dan JavaFX 21.0.8 di Linux. Semua basis data pengujian terisolasi dan berisi data contoh. Peluncuran `./mvnw -Dwhatif.dataDir=… javafx:run` membuka jendela dan membuat basis data contoh pada direktori yang diberikan.

## Otomatis

`./mvnw clean verify` menjalankan 16 tes JUnit tanpa kegagalan. Tes mencakup dua metode agregasi, bobot relatif, kategori kosong, semua skor kosong atau dikeluarkan, nol eksplisit, bonus, batas huruf eksak, pembulatan skor aman, dan proyeksi dari skor yang ditampilkan. Kasus 49,5/50 dengan maksimum bebas 100 menghasilkan kebutuhan 73,65 pada target 82,1%. Tes juga memeriksa kondisi target sudah aman, tanpa baris bebas, kebutuhan di atas maksimum, dan validasi desimal.

Tes SQLite memeriksa mulai ulang, penghapusan berantai, penolakan penyimpanan atas snapshot lama, rollback setelah penyimpanan gagal, percobaan ulang memakai draf yang sama, serta keadaan aktual setelah perubahan simulasi, pemulihan, reset, dan pembukaan ulang.

Probe JavaFX di layar virtual X11 menjalankan alur tambah mata kuliah dan penilaian, pengaturan metode, tambah kategori dan pemblokiran penghapusan kategori berisi penilaian. Probe memicu kegagalan SQLite dengan trigger uji, memeriksa nama dan skor 0 masih ada di formulir, lalu menyimpan draf yang sama setelah trigger dilepas. Probe juga memeriksa pemilihan baris dan buka editor dengan Enter, sinkronisasi target, hilangnya saran ketika target tidak valid, perubahan metadata nilai bebas, penilaian hipotetis dan asumsi tetap, pengecualian dan pemulihan, pembatalan saat reset, keluar, pindah mata kuliah, dan permintaan tutup, serta kondisi aktual dan simulasi baru setelah jendela dimulai ulang.

## Tampilan dan skala

Antarmuka dirender pada 1366 × 768 dan 960 × 680. Skala keluaran JavaFX 1,25 diemulasikan di Linux pada ukuran logis 1090 × 600. Posisi tombol dalam jendela diperiksa pada skala tersebut. Inspeksi visual menggunakan tangkapan layar hasil render JavaFX. Fokus keyboard pada target dan tabel serta kejelasan status baris diperiksa. Kasus uji pengguna manusia dan verifikasi DPI Windows belum dilakukan.

JavaFX melaporkan judul dirender sebagai **Merriweather Bold (Bold)** dan teks isi sebagai **System Regular (Regular)**. Helvetica dan Arial tidak tersedia di lingkungan uji. Rasio kontras token yang dihitung adalah 14,34:1 untuk teks utama pada putih, 5,55:1 untuk teks sekunder pada latar hangat, dan 6,55:1 untuk teks putih pada tombol utama.

| Kondisi | Tangkapan layar |
| --- | --- |
| Pembukaan awal dengan data kosong | [01-kosong.png](screenshots/01-kosong.png) |
| Nilai aktual yang masih sementara | [02-aktual.png](screenshots/02-aktual.png) |
| Target 82,1% dan saran 73,65 | [03-proyeksi.png](screenshots/03-proyeksi.png) |
| Target ambigu dan hasil disembunyikan | [04-target-invalid.png](screenshots/04-target-invalid.png) |
| Baris yang dikeluarkan tetap terlihat | [05-dikeluarkan.png](screenshots/05-dikeluarkan.png) |
| Saran di atas maksimum pada 960 × 680 | [06-bonus-960x680.png](screenshots/06-bonus-960x680.png) |
| Penghapusan kategori berisi penilaian diblokir | [07-kategori.png](screenshots/07-kategori.png) |
| Formulir mempertahankan draf ketika simpan gagal | [08-simpan-gagal.png](screenshots/08-simpan-gagal.png) |
| Skala JavaFX 125% | [09-skala-125.png](screenshots/09-skala-125.png) |

## Batas verifikasi referensi

Drive `D:` tidak terpasang di workspace Linux. Laporan investigasi, koleksi tangkapan layar Power Planner, dan proyek Tutup Buku tidak tersedia untuk diperiksa. Kebijakan aplikasi diambil dari keputusan pada spesifikasi implementasi dan diuji secara independen. Perilaku Power Planner, versi dependensi lama, dan kasus pembulatan integer multi-kategori Power Planner belum bisa diverifikasi di lingkungan ini.

## Audit lanjutan dan persiapan paket Windows

Arsip sumber lolos pemeriksaan ZIP. Git bundle memuat riwayat lengkap hingga `d42fbef1bdefa6bfb70d4dd3b1b6a6d508f1cab1`, sama dengan commit awal workspace. Proyek lokal digunakan langsung tanpa ekstraksi menimpa berkas.

| Keputusan produk | Hasil tinjauan kode dan tes sumber |
| --- | --- |
| Bobot relatif dan dua agregasi | Bobot kategori peserta dinormalisasi. Di dalamnya, porsi dihitung dari maksimum penilaian atau dibagi sama rata sesuai metode. |
| Kosong, nol, dan pengecualian | Skor kosong diabaikan pada hasil aktual. Nol tetap dievaluasi. Hasil simulasi memakai baris bebas untuk proyeksi dan mengabaikan baris yang dikeluarkan. |
| Override, pemulihan, dan penyimpanan | Simulasi menyimpan salinan kerja, asumsi, dan pengecualian di memori. Penyimpanan aktual terpisah. Pemulihan, reset, dan keluar diuji terhadap basis data sementara. |
| Proyeksi dan klasifikasi | Rasio bersama dihitung eksak. Saran skor dibulatkan ke atas dan dipakai untuk menghitung ulang proyeksi. Huruf memakai nilai eksak. Saran di atas maksimum memperlihatkan kebutuhan bonus. |
| Parser dan data setelah restart | Koma dan titik menghasilkan desimal yang sama. Masukan ambigu ditolak. Tes restart SQLite sekarang menggunakan subfolder dengan spasi. |

`./mvnw clean verify` lulus dengan 16 tes sumber di Linux. POM dan versi dependensi runtime tetap sama. Maven menyelesaikan artefak JavaFX dengan classifier `win`. Header PE `glass.dll` pada JavaFX dan `sqlitejdbc.dll` pada SQLite JDBC diperiksa sebagai x64. Script PowerShell Windows lolos parsing pada PowerShell 7.4 dan berhenti pada pemeriksaan platform ketika dijalankan di Linux.

**Uji paket Linux.** Adaptasi alur yang sama untuk Linux membuat app-image dengan JDK 21 beserta ZIP, notis lisensi, dan manifest hash. Hash ZIP serta manifest berkas hasil ekstraksi cocok. Launcher hasil ekstraksi berjalan dari folder berspasi dengan `JAVA_HOME` dilepas dan PATH kosong dari executable Java maupun Maven. Uji menggunakan basis data demo terisolasi. Pada 1366 × 768, launcher membuka layar kosong, menyimpan data, memproyeksikan 82,1% dengan saran 73,65, menyembunyikan hasil untuk target ambigu, dan menampilkan kebutuhan bonus. Pengecualian, penyertaan kembali, pembatalan konfirmasi, serta penutupan dan pembukaan ulang berhasil. Setelah buka ulang, nilai Kuis tetap 49,5, nilai Ujian tetap kosong, dan target What If kembali ke 85%. Log launcher melaporkan Merriweather Bold dan System Regular. [Tangkapan layar app-image Linux](screenshots/10-linux-app-image.png)

**Pemeriksaan yang tertahan.** Drive `D:` dan bukti investigasi tetap tidak terpasang. Pengujian Windows native, font Windows, DPI Windows, pembuatan Windows app-image, dan pengujian EXE hasil ekstraksi masih tertahan karena sesi ini tidak menyediakan host Windows. Langkah build dan verifikasi Windows tersedia di [panduan app-image](kemas-windows.md).

## Kelayakan, persentase, dan skenario tersimpan

`./mvnw clean verify` lulus dengan 26 tes JUnit pada JDK 21.0.13 dan JavaFX 21.0.8. Uji kelayakan mencakup kedua metode agregasi, batas eksak dan selisih sangat kecil, skor bonus aktual, asumsi tetap di atas maksimum, nilai bebas dengan maksimum pecahan, kategori kosong, seluruh baris dikeluarkan, dan kebutuhan 135/100 untuk target 85% dengan maksimum 76,25%. Nilai sementara 80% diuji terpisah dari kontribusi 24 poin persentase dan bobot terwakili 30%.

Uji SQLite memakai basis data sementara. Uji memastikan total 99,99% dan 100,01% ditolak, draf 100% disimpan atomik, penilaian dan mata kuliah lain tetap utuh, serta konfigurasi lama dipertahankan sampai persetujuan. Skema lama diuji dengan bobot 2:1, termasuk selisih hasil akibat pembulatan. Kegagalan migrasi diuji tanpa mengubah versi atau menghapus data. Uji skenario mencakup beberapa skenario, isolasi antar mata kuliah, salinan independen, penyimpanan ulang, override dan pengecualian, penghitungan ulang, pembaruan data dasar dengan konflik, restart, serta kegagalan simpan yang dapat diulang.

Probe antarmuka JavaFX berjalan pada desktop virtual Linux dalam ukuran 1366 × 768 dan 960 × 650. Probe menguji editor bobot dan validasi total, alur target tak tercapai, penyimpanan dari dialog perpindahan, kegagalan lalu percobaan ulang simpan, buka skenario, salin, bandingkan, pembatalan perpindahan skenario dan penutupan, buang perubahan, reset, baca ulang snapshot historis, pembaruan data dasar sebagai draf, dan pembukaan ulang aplikasi. Basis data demo lama diperiksa terpisah dalam mode baca saja. Merriweather Bold dan System Regular terdeteksi sebagai font judul dan isi di lingkungan uji. Uji pengguna manusia dan Windows native/DPI belum dilakukan untuk perubahan ini.

| Kondisi | Tangkapan layar |
| --- | --- |
| Workspace kosong | [11-kosong.png](screenshots/11-kosong.png) |
| Draf bobot 99,99% | [12-bobot-invalid.png](screenshots/12-bobot-invalid.png) |
| Nilai sementara dan kontribusi | [13-nilai-sementara.png](screenshots/13-nilai-sementara.png) |
| Target 85% dan maksimum 76,25% | [14-target-tidak-tercapai.png](screenshots/14-target-tidak-tercapai.png) |
| Draf belum disimpan | [15-skenario-diubah.png](screenshots/15-skenario-diubah.png) |
| Skenario tersimpan | [16-skenario-tersimpan.png](screenshots/16-skenario-tersimpan.png) |
| Perbandingan skenario | [17-bandingkan.png](screenshots/17-bandingkan.png) |
| Data dasar lama pada 960 × 650 | [18-data-dasar-lama-960x650.png](screenshots/18-data-dasar-lama-960x650.png) |

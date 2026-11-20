# Verifikasi What If 1.0

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

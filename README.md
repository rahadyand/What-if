# What If

Aplikasi desktop JavaFX untuk mencatat nilai mata kuliah dan mencoba skenario target nilai. Data aktual disimpan di SQLite lokal. Buka tab **What If** untuk mencoba asumsi tanpa mengubah data aktual.

## Menjalankan aplikasi

Pasang **JDK 21**, lalu cek `java -version` dan `javac -version`. Jika beberapa JDK terpasang, arahkan `JAVA_HOME` ke JDK 21. Maven Wrapper mengambil Maven dan dependensi pada build pertama. Aplikasi dijalankan di sesi desktop grafis dan dapat dipakai secara luring sesudah dependensinya tersedia.

Di Windows PowerShell, jalankan dari direktori proyek.

```powershell
.\mvnw.cmd test
.\mvnw.cmd javafx:run
```

Di Linux atau macOS, gunakan `./mvnw test` dan `./mvnw javafx:run`.

Lokasi basis data Windows adalah `%LOCALAPPDATA%\WhatIfStandalone\whatif.sqlite3`. Di Linux dan macOS, aplikasi memakai `~/.local/share/WhatIfStandalone/whatif.sqlite3`. Gunakan direktori uji kosong untuk meninjau aplikasi tanpa membuka data sehari-hari.

```powershell
$demo = Join-Path $env:TEMP ("whatif-demo-" + [guid]::NewGuid())
.\mvnw.cmd "-Dwhatif.dataDir=$demo" javafx:run
```

## Alur tinjau

1. Tambahkan mata kuliah. Kategori **Umum** tersedia dengan bobot 1. Di **Pengaturan**, pilih metode agregasi. Di **Kelola kategori**, tambah kategori atau ubah bobot relatif. Untuk menghapus kategori berisi penilaian, pindahkan penilaiannya lebih dulu.
2. Gunakan **Total poin** dan kategori **Umum** untuk kedua baris contoh berikut. Tambahkan penilaian `Kuis` dengan nilai `49,5` dari `50` dan `Ujian` dengan maksimum `100`. Biarkan nilai Ujian kosong. Hasil aktual sementara menunjukkan 99%. Gunakan **Simpan** pada setiap formulir aktual.
3. Masuk **What If**, ketik target `82,1`. Kolom **Diperlukan** pada Ujian menunjukkan `73,65` dan nilai proyeksi menunjukkan 82,1% (A-). Ubah nama Ujian untuk melihat nilainya tetap **Bebas**.
4. Coba buat penilaian hipotetis, ubah perlakuan nilai menjadi **Asumsi tetap**, keluarkan atau sertakan kembali baris, dan pulihkan data sumber dengan **Pulihkan aktual**. Atur ulang atau kembali ke **Nilai aktual**. Jika simulasi berubah, pilih **Batal** untuk kembali ke simulasi atau **Buang simulasi** untuk melanjutkan.
5. Pilih baris dengan keyboard dan tekan Enter untuk membuka editor. Coba target `82,1.0` untuk melihat validasi, lalu perbaiki dengan `82.1`. Pada target yang terlalu tinggi, kolom saran tetap menampilkan skor yang diperlukan dan panel memberi tahu bahwa bonus mungkin dibutuhkan.

## Dokumen proyek

- [Kebijakan perhitungan dan peta OOP](docs/desain.md)
- [Hasil verifikasi dan tangkapan layar](docs/verifikasi.md)
- [Atribusi dan lisensi](docs/atribusi.md)

## Di luar cakupan versi pertama

Akun, dashboard, agenda, jadwal, modul, layanan daring, AI, sinkronisasi, skenario bernama, perbandingan skenario, tanggal, catatan, skala kustom, pembulatan alternatif, Pass/Fail, presensi, nilai huruf langsung, override hasil akhir, ekstra kredit dengan maksimum nol, bobot nol, kode mata kuliah, SKS, IP semester, IPK, serta installer dan rilis.

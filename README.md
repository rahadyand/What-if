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

1. Tambahkan mata kuliah. Kategori **Umum** dimulai pada 100%. Di **Kelola kategori**, ubah beberapa kategori dalam satu draf sampai total tepat 100%, lalu simpan. Di **Pengaturan**, pilih cara menghitung hasil di dalam kategori.
2. Untuk contoh target, buat kategori Tugas 50%, UTS 25%, dan UAS 25%. Masukkan Tugas 80/100 dan UTS 45/100. Biarkan UAS 100 tanpa skor. Nilai sementara adalah sekitar 68,33%, kontribusinya 51,25 poin persentase, dan bobot terwakili 75%.
3. Buka **What If** dengan target 85%. Aplikasi menampilkan **Target Tidak Dapat Dicapai**, maksimum 76,25%, kekurangan 8,75 poin persentase, dan kebutuhan UAS 135 (35 poin skor di atas maksimum).
4. Klik **Simpan Simulasi**, beri nama, lalu buat variasi melalui **Opsi → Simpan sebagai salinan**. Buka kembali dari pemilih skenario. **Opsi → Bandingkan skenario** membandingkan dua versi yang sudah tersimpan.
5. Ubah satu nilai aktual, lalu buka skenario tadi. Status **Data dasar lama** menunjukkan bahwa skenario masih menggunakan salinan historis. **Opsi → Lihat data dasar** menampilkan isi dasarnya. **Opsi → Tinjau data aktual terbaru** memperbarui draf setelah Anda menyetujui tinjauannya. Simpan untuk mengganti versi skenario.
6. Pada perpindahan, pilih **Simpan Simulasi**, **Buang Perubahan**, atau **Batal** jika draf berubah. **Atur ulang** mengembalikan skenario tersimpan ke versi terakhir. Pada draf baru, tindakan ini mengembalikan penilaian dasar dan target 85%.

Untuk data lama, tutup aplikasi dan cadangkan berkas SQLite beserta berkas pendamping `-wal` atau `-shm` yang masih ada sebelum membuka versi ini. Gunakan **Tinjau bobot lama** untuk melihat usulan persentase dan selisih hasil sebelum menerapkan konversi. Alur pengujian proyek memakai direktori demo terpisah.

## Dokumen proyek

- [Perubahan What If](docs/perubahan-what-if.md)
- [Kebijakan perhitungan dan peta OOP](docs/desain.md)
- [Hasil verifikasi dan tangkapan layar](docs/verifikasi.md)
- [Atribusi dan lisensi](docs/atribusi.md)
- [Build dan peninjauan app-image Windows](docs/kemas-windows.md)

## Di luar cakupan versi pertama

Akun, dashboard, agenda, jadwal, modul, layanan daring, AI, sinkronisasi, tanggal, catatan, skala kustom, pembulatan alternatif, Pass/Fail, presensi, nilai huruf langsung, override hasil akhir, ekstra kredit dengan maksimum nol, bobot nol, kode mata kuliah, SKS, IP semester, IPK, serta installer dan publikasi GitHub Release.

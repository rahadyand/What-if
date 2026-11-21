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

## Fondasi akademik

Aplikasi terbuka di **Dashboard**. Tambah semester, pilih semester untuk melihat ringkasannya, dan gunakan **Jadikan Aktif** untuk menentukan semester startup. Dari grup semester, pilih **+ Mata kuliah** agar semester terisi otomatis. **Nilai Aktual → Pengaturan** mengubah semester, SKS, dan kehadiran. Mata kuliah dari basis data versi lama tersedia di **Belum dikelompokkan** sampai semester diisi.

## Nilai final dan indeks prestasi

Di **Nilai Aktual**, gunakan **Tetapkan Nilai Final** untuk mengonfirmasi hasil lengkap atau memasukkan hasil resmi kampus. Dashboard menghitung IP dan IPK dari angka mutu final yang tersimpan. **Pengaturan Skala Nilai** di Dashboard mengubah skala untuk perhitungan aktual dan What If baru. Tinjau perubahan kandidat melalui **Tinjau Ulang Nilai Final**.

## Proyeksi Dashboard dan batas SKS

Simpan skenario di What If, lalu gunakan **Pilih skenario** di Dashboard pada mata kuliah yang belum final. Kartu proyeksi memakai pilihan pada semester yang dilihat. **Rincian Proyeksi** menampilkan sumber, kontribusi, dan cakupan. **Total SKS tercatat** menampilkan pemakaian dari batas 24 SKS per semester.

## Alur tinjau

1. Saat menambah mata kuliah, centang pilihan **UTS**, **UAS**, **Tugas**, **Projek**, dan **Praktikum** yang tercantum di RPS, lalu isi bobotnya. Gunakan **+ Kategori kustom** untuk kategori lain. Simpan saat total kategori aktif tepat 100%. **Kelola kategori** membuka kembali draf bobot. **Pengaturan** mengubah cara menghitung hasil di dalam kategori.
2. Untuk contoh target, buat kategori Tugas 50%, UTS 25%, dan UAS 25%. Masukkan Tugas 80/100 dan UTS 45/100. Biarkan UAS 100 tanpa skor. Nama seperti `UTS` atau `Project Akhir` dapat menyarankan kategori aktif. Anda tetap dapat memilih kategori lain. Untuk menonaktifkan kategori yang berisi penilaian, pindahkan setiap penilaian ke kategori aktif lain dari Nilai Aktual. Nilai sementara adalah sekitar 68,33%, kontribusinya 51,25 poin persentase, dan bobot terwakili 75%.
3. Buka **What If** dengan target 85%. Aplikasi menunjukkan **Tidak dapat dicapai dalam batas normal**. Kolom **Diperlukan** menampilkan `100/100 · Maksimum`. Gulir ke **Ringkasan Target Nilai** untuk melihat kekurangan 8,75 poin persentase, nilai akhir tertinggi 76,25% (B+), dan skor untuk tiap huruf.
4. Klik **Simpan Simulasi**. Untuk skenario baru, terima saran nama berdasarkan target, seperti `Target A-` atau `Target 84,5%`, atau ganti dengan nama pilihan Anda. Buat variasi melalui **Opsi → Simpan sebagai salinan** dan buka kembali dari pemilih skenario. Gunakan **Bandingkan dua skenario tersimpan** di ringkasan untuk melihat prediksi tanpa beralih dari draf. **Opsi → Bandingkan skenario** menampilkan perbandingan lebih detail.
5. Ubah satu nilai aktual, lalu buka skenario tadi. Status **Data dasar lama** menunjukkan bahwa skenario masih menggunakan salinan historis. **Opsi → Lihat data dasar** menampilkan isi dasarnya. **Opsi → Tinjau data aktual terbaru** memperbarui draf setelah Anda menyetujui tinjauannya. Simpan untuk mengganti versi skenario.
6. Ubah nama mata kuliah melalui **Pengaturan**. Baris di sidebar langsung mengikuti nama baru dan skenario tetap memakai data dasarnya. Pada perpindahan, pilih **Simpan Simulasi**, **Buang Perubahan**, atau **Batal** jika draf berubah. **Atur ulang** mengembalikan skenario tersimpan ke versi terakhir. Pada draf baru, tindakan ini mengembalikan penilaian dasar dan target 85%.

Untuk data lama, tutup aplikasi dan cadangkan berkas SQLite beserta berkas pendamping `-wal` atau `-shm` yang masih ada sebelum membuka versi ini. Gunakan **Tinjau bobot lama** untuk melihat usulan persentase dan selisih hasil sebelum menerapkan konversi. Alur pengujian proyek memakai direktori demo terpisah.

## Dokumen proyek

- [Fondasi Akademik, peta OOP, dan keputusan Tahap 2](docs/tahap-akademik.md)
- [Nilai final, IP, IPK, dan peta OOP](docs/nilai-final-dan-indeks.md)
- [Proyeksi Dashboard dan batas 24 SKS](docs/proyeksi-dashboard.md)
- [Perubahan What If](docs/perubahan-what-if.md)
- [Kebijakan perhitungan dan peta OOP](docs/desain.md)
- [Hasil verifikasi dan tangkapan layar](docs/verifikasi.md)
- [Atribusi dan lisensi](docs/atribusi.md)
- [Build dan peninjauan app-image Windows](docs/kemas-windows.md)

## Di luar cakupan Tahap 3

Akun, agenda, jadwal, modul, layanan daring, AI, sinkronisasi, tanggal, catatan, pembulatan alternatif, Pass/Fail, aturan penilaian berdasarkan kehadiran,  ekstra kredit dengan maksimum nol, bobot nol, kode mata kuliah, rekomendasi target IP/IPK, serta installer dan publikasi GitHub Release.

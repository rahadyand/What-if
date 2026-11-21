# Tahap 1 — Fondasi Akademik

Dashboard terbuka saat aplikasi dimulai. Sidebar membedakan semester yang sedang dilihat dan semester aktif yang disimpan. Pilih judul semester untuk melihat ringkasannya. Gunakan **Jadikan Aktif** untuk menentukan semester yang ditampilkan dan dibuka di sidebar pada startup berikutnya. Semester diurutkan dari tahun ajaran terbaru, lalu nomor terbesar pada tahun yang sama. Untuk urutan yang setara, entri terbaru muncul lebih dulu.

**Tambah Mata Kuliah** dari suatu grup memilih semester tersebut. **Pengaturan** pada Nilai Aktual dapat memindahkan mata kuliah, mencatat SKS, dan mengisi kehadiran. Jumlah SKS di Dashboard mencakup SKS yang sudah dicatat pada semester yang sedang dilihat. Kehadiran menampilkan **Belum dicatat**, **Belum ada pertemuan** untuk 0/0, atau jumlah hadir dan persentase. Kehadiran bersifat informasional.

## Migrasi SQLite

`CourseStore` meningkatkan skema ke versi 2 dalam satu transaksi. Data lama di versi 0 melewati migrasi versi 1 yang sudah ada. Semester, SKS, dan kehadiran mata kuliah lama dimulai dengan nilai kosong. Mata kuliah tersebut tersedia di **Belum dikelompokkan**. Buka **Nilai Aktual → Pengaturan** untuk melengkapinya.

Pemindahan semester memperbarui mata kuliah pada ID yang sama. Bobot kategori, penilaian, nilai aktual, dan snapshot skenario tetap tersimpan. Snapshot What If berisi masukan nilai. Semester, SKS, dan kehadiran berada di data mata kuliah aktual dan tidak memicu status data dasar lama pada skenario.

## Tanggung jawab kelas untuk laporan OOP

| Kelas | Tanggung jawab |
| --- | --- |
| `Semester` | Menyimpan identitas semester, label, dan tahun ajaran. Menentukan pembanding untuk urutan semester. |
| `Course` dan `AcademicDetails` | `Course` menggabungkan identitas mata kuliah, nilai, serta metadata akademik. `AcademicDetails` memvalidasi SKS dan pasangan angka kehadiran. |
| `CourseStore` | Menjalankan migrasi transaksional, menyimpan semester aktif, dan membaca serta menulis data akademik pada SQLite. |
| `AcademicDashboard` dan `AcademicSidebar` | Menampilkan ringkasan semester, pintasan layar nilai, daftar grup, dan pilihan aktif. |
| `WhatIfApp` dan `Editor` | Mengatur perpindahan layar dan konfirmasi draf. `Editor` memakai alur validasi dan simpan yang sama untuk form semester dan mata kuliah. |

## Keputusan Tahap 2

- **IP dari nilai final sejauh ini** boleh menampilkan angka dari mata kuliah yang sudah difinalisasi, disertai SKS yang masuk perhitungan dan total SKS semester. Gunakan **IP Semester Final** setelah seluruh mata kuliah di semester tersebut memenuhi syarat perhitungan. **IPK Aktual** memakai mata kuliah final yang memenuhi syarat dari semua semester, termasuk semester yang masih berjalan.
- Finalisasi adalah tindakan eksplisit yang menyimpan huruf dan angka mutu yang sudah dikonfirmasi. Pengguna dapat mengonfirmasi hasil perhitungan atau mengisi nilai resmi secara manual dengan sumbernya.
- Perubahan bobot atau skala mempertahankan hasil final tersimpan. Jika hasil baru berbeda, **Tinjau Ulang Nilai Final** menampilkan nilai lama dan baru, lalu menawarkan untuk mempertahankan hasil lama atau mengonfirmasi pembaruan. Hasil yang setara tidak perlu dikonfirmasi ulang. IP dan IPK Aktual memakai hasil final tersimpan sampai pembaruan dikonfirmasi.
- Nilai sementara dan What If baru memakai konfigurasi penilaian terkini. Skenario tersimpan mengikuti snapshot historis dan alur penyegaran eksplisit yang tersedia.
- Pemilihan skenario tersimpan untuk proyeksi Dashboard masuk Tahap 3. Rekomendasi target IP masuk Tahap 4. Kartu IP dan IPK ditambahkan pada tahap perhitungannya.

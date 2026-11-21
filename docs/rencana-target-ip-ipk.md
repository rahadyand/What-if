# Tahap 4 — Rencana Target IP dan IPK

## Alur dari Dashboard

Pilih semester yang ingin dilihat, lalu buka **Rencanakan Target IP/IPK**. Pilih **Target IP Semester** atau **Target IPK** dan isi angka mutu menggunakan koma atau titik. Batas input mengikuti angka mutu maksimum pada skala aktif.

Planner menampilkan proyeksi awal jika seluruh kontribusi yang dihitung tersedia. **Sudah Memenuhi** hanya berlaku jika hasil awal lengkap mencapai target. **Bisa Dicapai** menampilkan satu kombinasi yang memenuhi target. **Tidak Dapat Dicapai** menampilkan batas dan kekurangan dalam poin IP atau IPK. Apabila angka tampil dua desimal dan pembulatan mengubah nilainya, batas sebelum pembulatan serta perhitungannya tampil di bawah angka utama.

## Sumber dan cakupan

IP Semester mencakup mata kuliah di semester yang sedang dilihat. IPK mencakup kontribusi final yang memenuhi syarat dari semua semester serta mata kuliah belum final pada semester yang sedang dilihat. Setiap ID berkontribusi paling banyak sekali. Mata kuliah belum final di semester lain berada di luar cakupan.

- Hasil final memakai huruf dan angka mutu yang sudah dikonfirmasi.
- Skenario pilihan yang masih dapat digunakan menyumbang hasil awal dan menyediakan asumsi serta masukan tetap untuk mencari kombinasi lain.
- Jika belum ada pilihan skenario, planner memakai struktur penilaian aktual. Jika masih ada skor bebas, struktur itu bisa dipakai untuk merencanakan tanpa menganggap hasil awal lengkap.
- SKS yang kosong, struktur kategori yang belum lengkap, dan skenario terpilih yang perlu ditinjau memunculkan **Belum Lengkap**. Pilihan skenario yang bermasalah dapat ditinjau melalui What If atau diganti dari Dashboard. **Pakai data aktual untuk sesi ini** hanya mengganti sumber di dialog.
- IPK memakai keputusan pengulangan yang sudah ada untuk grup yang sama. Jika grup masih menunggu keputusan, tinjau kontribusi dari dialog. Keputusan tambahan tersebut hanya berlaku pada sesi perencanaan.

Buka **Rincian skor di What If** untuk memeriksa rekomendasi pada draf baru. Draf memakai dasar yang sama dan target per mata kuliah dari kombinasi. Keluar dari planner mempertahankan data aktual, hasil final, pilihan Dashboard, dan skenario tersimpan. Opsi **Simpan Simulasi** tetap tersedia sebagai tindakan eksplisit pada layar What If.

## Perhitungan dan OOP

Planner mengevaluasi hasil solver untuk setiap ambang skala serta hasil saat semua skor bebas mencapai maksimum normal. Huruf ditentukan dari hasil efektif sebelum pembulatan. Kombinasi tertinggi memakai angka mutu terbaik yang benar-benar tersedia pada setiap mata kuliah. Planner kemudian mencoba rekomendasi yang lebih rendah sesuai urutan mata kuliah selama total berbobot masih mencapai target.

| Kelas | Tanggung jawab |
| --- | --- |
| `TargetPlanner` | Memilih sumber, memeriksa kelengkapan, membandingkan angka tanpa pembulatan, dan menyusun kombinasi. |
| `DashboardProjection` dan `Calculation` | Menyediakan status skenario serta hasil solver yang dipakai untuk mengevaluasi kandidat dan batas normal. |
| `GradeScale` dan `AcademicAverage` | Memetakan hasil ke huruf, menerapkan tinjauan pengulangan, dan menjumlahkan angka mutu × SKS. |
| `IndexPlannerView` dan `WhatIfApp` | Menampilkan kedua mode, menangani pilihan sesi, dan membuka draf What If dengan penjagaan navigasi yang sama. |

Perencanaan memakai skema SQLite dari Tahap 3. Batas 24 SKS tetap berlaku di formulir dan penyimpanan.

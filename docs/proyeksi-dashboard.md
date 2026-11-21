# Tahap 3 — Proyeksi Dashboard dan Batas 24 SKS

## Memilih sumber

Untuk mata kuliah yang belum final, simpan skenario di What If lalu pilih melalui **Pilih skenario** di Dashboard. Gunakan **Ganti skenario** untuk mengganti atau memilih **Tanpa skenario**. Mata kuliah final selalu menggunakan huruf dan angka mutu final tersimpan. Rincian menunjukkan sumber, SKS, huruf, angka mutu, dan kontribusi masing-masing mata kuliah.

Huruf proyeksi berasal dari hasil efektif perhitungan skenario. Skenario harus memakai data dasar terbaru, mewakili seluruh bobot kategori, dan menghasilkan proyeksi dalam batas normal. Asumsi di atas maksimum perlu diperbaiki. Skor yang sudah tercatat sebagai nilai aktual tetap dipakai. Untuk data dasar lama, buka **Tinjau What If → Opsi → Tinjau data aktual terbaru**, tinjau draf, lalu simpan kembali. Pilihan yang mengarah ke skenario terhapus atau tidak dapat dibaca tetap dapat diganti atau dikosongkan.

## Cakupan perhitungan

- **IP Semester Proyeksi** memakai setiap mata kuliah di semester yang dilihat. Jika ada SKS atau sumber yang kurang, status menjadi **Proyeksi belum lengkap**. Nama mata kuliah dan masalahnya tersedia di rincian.
- **IPK Proyeksi** menggabungkan kontribusi final yang memenuhi syarat dari seluruh semester dengan kontribusi skenario untuk mata kuliah belum final pada semester yang dilihat. Setiap ID dihitung paling banyak sekali. Mata kuliah belum final di semester lain ditampilkan sebagai di luar cakupan.
- Tinjauan calon pengulangan yang melibatkan skenario disimpan terpisah dari tinjauan IPK Aktual. Grup tetap perlu dipilih secara eksplisit sebelum IPK Proyeksi dianggap lengkap. Grup yang hanya berisi nilai final mengikuti tinjauan aktual.
- Nilai dipertahankan penuh saat menjumlahkan `SKS × angka mutu`. Pembulatan dua desimal dilakukan saat menampilkan hasil. Kartu menampilkan cakupan masing-masing. Skenario yang masuk menambah ID yang belum final sehingga cakupan berbeda dari aktual. Saat semua mata kuliah final, Dashboard menampilkan hasil aktual sebagai hasil utama.

## Batas SKS dan migrasi

Dashboard menampilkan SKS tercatat terhadap batas 24. Penambahan, perubahan SKS, dan pemindahan dinilai berdasarkan jumlah akhir semester tujuan. Pada semester lama yang sudah melebihi 24, perubahan yang mempertahankan atau mengurangi jumlah dapat disimpan. Perubahan yang menambah kelebihan ditolak.

Migrasi transaksional dari skema 3 ke 4 menambah tabel pilihan skenario dan tinjauan pengulangan proyeksi. Pilihan mulai kosong. Mata kuliah, kehadiran, penilaian, hasil final, tinjauan aktual, dan snapshot skenario dipertahankan. Memilih skenario memvalidasi kepemilikan dan status belum final pada saat simpan.

## Tanggung jawab kelas untuk laporan OOP

| Kelas | Tanggung jawab |
| --- | --- |
| `DashboardProjection` | Menentukan sumber final atau skenario terpilih, memeriksa kelengkapan, dan menyusun cakupan semester serta kumulatif. |
| `AcademicAverage` | Menjumlahkan kontribusi SKS serta menerapkan tinjauan pengulangan pada kumpulan yang dihitung. |
| `SemesterSks` | Menghitung total tercatat dan memvalidasi batas yang sama pada formulir dan penyimpanan. |
| `CourseStore` | Menjalankan migrasi, memeriksa kepemilikan skenario, dan menyimpan pilihan serta tinjauan dalam transaksi. |
| `AcademicDashboard` dan `ProjectionView` | Menampilkan kartu, status skenario per mata kuliah, formulir pilihan, dan rincian kontribusi. |

Rekomendasi target IP/IPK tetap masuk Tahap 4.

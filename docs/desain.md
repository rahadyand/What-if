# Desain dan kebijakan perhitungan

## Input dan nilai aktual

Nilai, maksimum, bobot, serta target menerima titik atau koma desimal. Masukkan digit sebelum dan sesudah tanda desimal, tanpa pemisah ribuan. Skor kosong berarti belum diketahui. Nol adalah nilai yang sah. Maksimum dan bobot harus positif. Skor bonus dapat melampaui maksimum.

Di Nilai Aktual, hanya penilaian dengan skor yang dihitung di dalam setiap kategori. Nilai sementara membagi total kontribusi kategori yang sudah dapat dihitung dengan bobot yang terwakili. Kontribusi tiap kategori terhadap nilai akhir memakai persentase kategori yang dikonfigurasi. Contoh UTS 30% dengan hasil 80% menghasilkan nilai sementara 80%, kontribusi 24 poin persentase, dan bobot terwakili 30%. Hasil tetap sementara jika ada penilaian atau bobot yang belum terwakili. Tanpa skor yang diketahui, panel menampilkan **Belum ada nilai**.

Dalam satu kategori, **Total poin** memakai jumlah skor dibagi jumlah maksimum. **Rata-rata persentase** menghitung rasio tiap penilaian lalu meratakannya. Untuk 8/10 dan 60/100, hasilnya masing-masing 68/110 (sekitar 61,82%) dan 70%. Setelah menghitung nilai kategori, sistem menerapkan persentase kategori terhadap nilai akhir. Total konfigurasi baru harus tepat 100% dan setiap kategori berbobot positif. Penilaian dengan maksimum sama tetap dapat memiliki kontribusi berbeda.

Editor mata kuliah baru menawarkan lima preset tanpa bobot bawaan. Kategori kustom memakai editor yang sama. Hanya kategori aktif disimpan dan disertakan dalam total bobot. Mata kuliah lama tetap memakai kategori dan UUID yang tersimpan. Menonaktifkan kategori berisi penilaian memerlukan pemindahan penilaian secara eksplisit.

Saran kategori mencocokkan kata atau frasa utuh tanpa membedakan kapitalisasi. `Projek`, `Project`, dan `Proyek` diperlakukan sebagai padanan. Kategori aktif yang cocok tunggal mengisi pilihan awal. Jika saran ambigu atau tidak ada, pengguna menentukan kategori. Pilihan yang sudah ditetapkan tetap berlaku saat nama berubah. Bobot yang tampil mengikuti kategori terpilih, dan kategori hipotetis memakai konfigurasi dari data dasar skenario.

| Huruf | Batas bawah | Poin mata kuliah |
| --- | ---: | ---: |
| A | 85% | 4,00 |
| A- | 80% | 3,70 |
| B+ | 75% | 3,30 |
| B | 70% | 3,00 |
| C+ | 65% | 2,70 |
| C | 60% | 2,00 |
| D | 55% | 1,00 |
| E | 0% | 0,00 |

Klasifikasi huruf memakai pecahan eksak. Persentase ringkasan ditampilkan hingga dua desimal dengan pemotongan, sehingga angka yang terlihat tidak melampaui nilai eksak. Persentase di atas 100 tetap A dengan poin 4,00.

## Simulasi dan target minimum

What If membuat draf dari mata kuliah yang dipilih atau dari snapshot skenario yang dibuka. Nilai aktual berawal sebagai nilai tetap. Target huruf mengisi batas bawahnya. Input angka menyimpan digit desimal pengguna, termasuk ketika target melebihi 100%.

Dalam konfigurasi persentase, setiap penilaian yang disertakan mempunyai koefisien sebesar persentase kategori terhadap 100 dikali porsinya dalam kategori. Total poin memakai rasio maksimum penilaian terhadap total maksimum kategori. Rata-rata persentase memberi porsi sama rata. Baris yang dikeluarkan tidak ikut, dan kategori yang tidak memiliki baris tetap tidak terwakili. Persentase kategori kosong tidak dialihkan. Pada konfigurasi relatif historis, What If tetap memakai penyebut kategori peserta yang digunakan versi lama.

Dengan nilai dan target sebagai rasio, perhitungan mengikuti persamaan:

```text
maksimum normal = kontribusiTetap + porsiBebas
persentaseBersama = max(0, (target - kontribusiTetap) / porsiBebas)
```

Saran untuk setiap nilai bebas adalah maksimum penilaian dikali persentase bersama, dibulatkan ke atas sampai dua desimal. Apabila target bisa dicapai tetapi pembulatan melewati maksimum pecahan, gunakan maksimum eksak. Proyeksi dihitung ulang dari skor saran yang tampil. Jika target melewati maksimum normal, ringkasan menampilkan maksimum yang dapat dicapai dan selisih target dalam **poin persentase**. Kolom saran tetap menampilkan kebutuhan matematika dan **poin skor** di atas maksimum penilaian. Tabel menampilkan maksimum normal dan poin tambahan secara terpisah, misalnya `100 (+35 bonus)`. Ringkasan menyimpan makna kebutuhan matematis 135/100 dan proyeksi bersyarat pada izin dosen. Kelayakan serta klasifikasi huruf selalu memakai pecahan eksak. Nilai bonus aktual tetap masuk perhitungan. Asumsi hipotetis yang melewati maksimum menjadikan hasil bersyarat.

Ringkasan di bawah tabel membaca hasil target dan semua ambang dari `Grade`. `Calculation.targetReport` menyiapkan koefisien sekali dari draf saat ini lalu menjalankan solver yang sama untuk target aktif dan setiap huruf. Setiap baris dengan beberapa penilaian bebas memakai satu kombinasi skor berpersentase sama sebelum pembulatan. Analisis ambang dan perbandingan dua skenario tidak mengubah target atau isi draf.

## Penyimpanan dan pembaruan data dasar

SQLite versi skema 1 menambah status konfigurasi lama, tabel riwayat bobot lama, dan tabel skenario. Migrasi berlangsung dalam transaksi. Nilai dan identitas lama dipertahankan. Konversi bobot memakai desimal eksak jika memungkinkan. Jika rasionya berulang, sistem menawarkan pembagian ke total 100% dan menampilkan selisih hasil berdasarkan data saat ini. Usulan 2:1, misalnya, adalah 66,67% dan 33,33%. Simpan untuk memakai usulan atau penyesuaian Anda. Bobot asli diarsipkan saat konversi berhasil.

Skenario memiliki UUID, nama, dan snapshot berversi berisi konfigurasi, penilaian dasar, target, serta baris draf. Penyimpanan skenario hanya menulis tabel skenario. Proyeksi tidak disimpan. Perubahan nilai, struktur, atau konfigurasi aktual membuat dasar lama terlihat sebagai historis. `Course.sameGradeInputs` mengecualikan nama mata kuliah dari pemeriksaan tersebut. Perubahan nama disimpan berdasarkan UUID mata kuliah yang sama. Tinjauan data terbaru mencocokkan UUID, mempertahankan edit yang kompatibel, dan menampilkan edit yang dilepas karena penilaian atau kategori hilang atau maksimum sumber berubah. Hasil tinjauan tetap berupa draf sampai disimpan.

## Peta tanggung jawab OOP

| Kelas | Tanggung jawab | Contoh untuk presentasi |
| --- | --- | --- |
| `Course`, `Course.Category`, `Course.Assessment` | Model aktual yang immutable, validasi, dan identitas UUID | Mengganti nama penilaian mempertahankan ID, dan kategori yang masih dirujuk tidak dapat dihapus. |
| `Simulation`, `Simulation.Row` | Menjaga draf, target, pengecualian, pemulihan, dan tinjauan data dasar | `restore` mengambil nilai sumber dari baseline. Operasi ini tidak mengenal SQLite. |
| `Aggregation` | Abstraksi koefisien penilaian dengan dua implementasi enum | `shares` dipanggil dengan cara sama pada dua metode, tetapi total poin dan rata-rata persentase menerapkan perilaku berbeda. |
| `Calculation`, `Exact`, `Grade`, `Numbers` | Evaluasi dan solver, aritmetika pecahan, klasifikasi huruf, dan parser desimal | Target aktif dan semua ambang huruf memakai solver yang sama. `84,5` tetap A- sebelum pemformatan. |
| `CourseStore`, `Scenario`, `ScenarioSnapshot` | Migrasi SQLite, transaksi aktual/skenario, dan snapshot input | Penyimpanan skenario mempunyai UUID tersendiri dan memeriksa versi tersimpan sebelum pembaruan. |
| `CategoryConfiguration`, `CategoryEditor`, `WeightConversion` | Preset, draf aktivasi, validasi persentase, dan tinjauan konversi | Hanya kategori aktif masuk total dan perubahan diterapkan dalam satu transaksi. |
| `CategorySuggestion`, `Editor.CategoryChoice` | Pencocokan nama dan panduan pada editor | Saran tidak mengganti kategori yang dipilih eksplisit. `Calculation` membaca UUID kategori yang tersimpan. |
| `WhatIfApp`, `Editor`, `TargetSummary`, `ScenarioComparison` | Tampilan, interaksi, validasi formulir, dan koordinasi pekerjaan I/O | `Editor` menyimpan isian sampai operasi berhasil. `WhatIfApp` menjalankan I/O lewat JavaFX `Task`. |

Koleksi generik menyimpan kategori, penilaian, dan saran menurut ID. Exception validasi menjaga objek tetap sah. Prepared statement dan try-with-resources mengelola I/O tanpa SQL di event handler.

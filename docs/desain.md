# Desain dan kebijakan perhitungan

## Input dan nilai aktual

Nilai, maksimum, bobot, serta target menerima titik atau koma desimal. Masukkan digit sebelum dan sesudah tanda desimal, tanpa pemisah ribuan. Skor kosong berarti belum diketahui. Nol adalah nilai yang sah. Maksimum dan bobot harus positif. Skor bonus dapat melampaui maksimum.

Perhitungan aktual mengambil penilaian yang sudah memiliki skor. Kategori tanpa skor yang diketahui dikeluarkan dari penyebut bobot. Hasil berstatus **Nilai sementara** selama sebagian penilaian masih kosong. Tanpa skor yang diketahui, panel menampilkan **Belum ada nilai**.

Dalam satu kategori, **Total poin** memakai jumlah skor dibagi jumlah maksimum. **Rata-rata persentase** menghitung rasio tiap penilaian lalu meratakannya. Untuk 8/10 dan 60/100, hasilnya masing-masing 68/110 (sekitar 61,82%) dan 70%. Bobot kategori dinormalisasi atas kategori yang ikut dihitung. Bobot 100 dan 50 menghasilkan proporsi yang sama dengan 2 dan 1.

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

What If membuat salinan kerja dalam memori dari mata kuliah yang dipilih. Nilai aktual diketahui berawal sebagai nilai tetap. Formulir simulasi menyimpan perlakuan nilai dan input manual. Skor saran hanya hidup di hasil perhitungan sehingga mengubah nama penilaian bebas tidak membuat skornya menjadi asumsi. Baris yang dikeluarkan tetap terlihat. Pengaturan bobot dan metode selalu berasal dari data aktual. Konfirmasi buang melindungi perubahan sementara saat pindah mata kuliah, keluar, mengatur ulang, atau menutup aplikasi.

Target huruf mengisi batas bawahnya. Input angka menyimpan digit desimal pengguna dan memperbarui huruf yang bersesuaian. Target dapat melebihi 100%.

Setiap penilaian yang disertakan mempunyai koefisien. Koefisien tersebut adalah porsi bobot kategorinya dikali porsinya di dalam kategori. Total poin memakai rasio maksimum penilaian terhadap total maksimum kategori. Rata-rata persentase memberi porsi sama rata. Pada What If, semua baris bebas yang disertakan ikut menentukan koefisien, termasuk kategori yang seluruh barisnya bebas.

Dengan semua nilai dinyatakan sebagai rasio, perhitungan proyeksi mengikuti persamaan

```text
target = kontribusiTetap + porsiBebas × persentaseBersama
persentaseBersama = max(0, (target - kontribusiTetap) / porsiBebas)
```

Setiap skor saran adalah maksimum penilaian dikali persentase bersama, dibulatkan ke atas ke dua desimal. Proyeksi dihitung ulang dari skor saran yang terlihat. Jika tidak ada nilai bebas, hasil tetap dibandingkan langsung dengan target. Jika tidak ada baris yang disertakan, hasil tidak tersedia. Jika saran melampaui maksimum, panel menyatakan perlu bonus dan saran asli tetap ditampilkan. Persentase bersama adalah salah satu solusi untuk mencapai target. Solver tidak mencari kombinasi integer.

## Peta tanggung jawab OOP

| Kelas | Tanggung jawab | Contoh untuk presentasi |
| --- | --- | --- |
| `Course`, `Course.Category`, `Course.Assessment` | Model aktual yang immutable, validasi, dan identitas UUID | Mengganti nama penilaian mempertahankan ID, dan kategori yang masih dirujuk tidak dapat dihapus. |
| `Simulation`, `Simulation.Row` | Menjaga salinan sementara, target, pengecualian, dan pemulihan | `restore` mengambil nilai sumber dari baseline. Operasi ini tidak mengenal SQLite. |
| `Aggregation` | Abstraksi koefisien penilaian dengan dua implementasi enum | `shares` dipanggil dengan cara sama pada dua metode, tetapi total poin dan rata-rata persentase menerapkan perilaku berbeda. |
| `Calculation`, `Exact`, `Grade`, `Numbers` | Evaluasi dan solver, aritmetika pecahan, klasifikasi huruf, dan parser desimal | Kedua hasil memakai evaluator yang sama. `84,5` ditentukan sebagai A- sebelum pemformatan. |
| `CourseStore` | I/O SQLite dan transaksi data aktual | Penyimpanan gagal membatalkan perubahan dalam transaksi. Penyimpanan membandingkan snapshot sebelumnya untuk mencegah penimpaan dari jendela lain. |
| `WhatIfApp`, `Editor` | Tampilan, interaksi, validasi formulir, dan koordinasi pekerjaan I/O | `Editor` menyimpan isian sampai operasi berhasil. `WhatIfApp` menjalankan I/O lewat JavaFX `Task`. |

Koleksi generik menyimpan kategori, penilaian, dan saran menurut ID. Exception validasi menjaga objek tetap sah. Prepared statement dan try-with-resources mengelola I/O tanpa SQL di event handler.

# Tahap 2 — Nilai Final, IP, dan IPK Aktual

## Alur pengguna

Pada **Nilai Aktual**, pilih **Tetapkan Nilai Final**. Konfirmasi hasil perhitungan jika bobot kategori sudah seluruhnya terwakili dan semua penilaian memiliki nilai. Jika kampus sudah mengumumkan nilai resmi, pilih **Masukkan nilai resmi** dan lihat pratinjau huruf serta angka mutu sebelum konfirmasi. Jalur resmi dapat digunakan saat data penilaian belum lengkap.

**Tinjau Ulang Nilai Final** muncul ketika huruf atau angka mutu kandidat berbeda. Dialog menampilkan nilai lama dan usulan beserta sumbernya. **Batal** menutup dialog tanpa menyimpan. **Pertahankan nilai tersimpan** mencatat pilihan untuk kandidat tersebut. Memilih hasil perhitungan atau nilai resmi dan mengonfirmasi menyimpan nilai baru. Nilai resmi yang masih dipetakan ke angka mutu yang sama tetap bersumber resmi, walaupun hasil perhitungan aktual berbeda.

Buka **Pengaturan Skala Nilai** dari Dashboard. Gunakan persentase minimum antara 0 dan 100, cantumkan satu huruf dengan batas 0%, dan isi angka mutu dari 0 sampai maksimum skala. Huruf dan batas harus unik. Persentase mentah dipakai untuk menentukan huruf. Perubahan skala diterapkan pada perhitungan aktual dan What If baru. Skenario tersimpan tetap memakai snapshot sampai pengguna menyetujui tinjauan penyegaran draf dan menyimpannya.

## IP dan IPK

Jumlahkan **SKS × angka mutu final tersimpan** dan bagi dengan SKS yang masuk. Pembulatan dua desimal hanya untuk tampilan hasil akhir. Dashboard menampilkan **IP Semester Final** jika semua mata kuliah semester itu memiliki SKS dan nilai final. Saat sebagian belum memenuhi syarat, Dashboard menampilkan **IP dari nilai final sejauh ini** beserta cakupan SKS. IPK Aktual memakai kontribusi mata kuliah final dari seluruh semester, termasuk semester berjalan. E yang dikonfirmasi menyumbang nol angka mutu dan SKS-nya tetap masuk penyebut.

Calon pengulangan ditandai dari nama sama di antara mata kuliah yang memenuhi syarat. Kontribusi dalam grup tersebut menunggu peninjauan eksplisit untuk IPK. Di **Rincian IPK Aktual**, pilih entri yang akan dihitung. Pilihan berlaku untuk kumpulan ID yang ditinjau. Jika anggota grup berubah, grup tersebut menunggu peninjauan lagi. Kebijakan pengulangan ini hanya memengaruhi IPK. Catatan nilai dari setiap mata kuliah tetap tersedia.

## Migrasi dan OOP

Skema SQLite versi 3 menambahkan tabel skala, nilai final, dan tinjauan kontribusi IPK dalam transaksi. Mata kuliah lama mulai tanpa nilai final. Semester aktif, SKS, kehadiran, bobot, nilai aktual, dan byte snapshot yang sudah tersimpan dipertahankan. Snapshot versi 1 dibaca memakai skala awal. Snapshot versi 2 menyertakan skala historis.

| Kelas | Tanggung jawab |
| --- | --- |
| `FinalGrade` dan `FinalGradeEditor` | Memodelkan hasil serta sumber final, membandingkan kandidat dengan hasil tersimpan, dan menampilkan pilihan konfirmasi. |
| `Grade`, `GradeScale`, dan `GradeScaleEditor` | Memodelkan huruf, memvalidasi draft skala, mengklasifikasikan persentase eksak, dan menyediakan formulir skala. |
| `AcademicAverage` dan `AcademicIndexView` | Menentukan kontribusi yang memenuhi syarat, menghitung IP/IPK, dan menampilkan cakupan serta rincian. |
| `CourseStore` dan `ScenarioSnapshot` | Menjalankan migrasi dan penyimpanan atomik serta membaca skala historis skenario. |

Proyeksi Dashboard adalah Tahap 3. Rekomendasi target IP adalah Tahap 4.

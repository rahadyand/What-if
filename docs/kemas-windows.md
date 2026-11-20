# App-image Windows x64

Jalankan build pada Windows x64 dengan Git, PowerShell 5.1 atau lebih baru, dan JDK 21 x64 lengkap dengan `jdeps`, `jlink`, serta `jpackage`. Maven Wrapper mengambil dependensi yang dipatok di proyek. App-image memuat runtime Java dan bisa dibuka langsung setelah ZIP diekstrak. [Manual jpackage JDK 21](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)

## Pemeriksaan sumber

Di PowerShell, dari root proyek, arahkan `JAVA_HOME` ke JDK 21 dan jalankan tes serta aplikasi dengan data terisolasi.

```powershell
.\mvnw.cmd clean verify
$demo = Join-Path $env:TEMP ("What If source " + [guid]::NewGuid())
.\mvnw.cmd "-Dwhatif.dataDir=$demo" javafx:run
```

Periksa jendela kosong, data yang sudah tersimpan, What If, input tidak valid, kebutuhan bonus, serta konfirmasi saat keluar atau pindah. Gunakan jendela 1366 × 768 dan jendela lebih kecil. Catat ukuran dan pengaturan DPI Windows yang benar-benar digunakan. Konsol mencatat nama font JavaFX yang dirender untuk judul dan teks isi. Simpan tangkapan layar dan hasil pemeriksaan aktual sebelum mengemas.

## Build

```powershell
.\scripts\package-windows.ps1
```

Script menjalankan verifikasi Maven dan menyiapkan JAR aplikasi serta dependensi runtime hasil resolusi Maven. JavaFX memakai artefak Windows x64 dengan classifier `win`. `jdeps` dan deklarasi modul JavaFX menentukan modul JDK untuk runtime yang dibundel. `jpackage --type app-image` membuat launcher native dan runtime di folder baru `releases/<timestamp-UTC>/WhatIf/`. Script mengemas seluruh folder tersebut dan menghasilkan `SHA256SUMS.txt` untuk ZIP. Di dalam folder dan ZIP tersedia petunjuk membuka, provenance, notis lisensi, dan manifest SHA-256 berkas app-image.

## Uji ZIP pada Windows

Setelah build, gunakan ZIP dari folder timestamp hasil build. Cocokkan hash ZIP dari `Get-FileHash -Algorithm SHA256` dengan `SHA256SUMS.txt`. Ekstrak ke direktori uji yang mengandung spasi. Jalankan EXE dari hasil ekstraksi. Variabel lingkungan di contoh ini berlaku bagi proses uji dan dipulihkan setelah peluncuran.

```powershell
$zip = (Resolve-Path '<folder release>\WhatIf-1.0.0-windows-x64.zip').Path
$extract = Join-Path $env:TEMP ("What If extracted " + [guid]::NewGuid())
$demo = Join-Path $env:TEMP ("What If packaged data " + [guid]::NewGuid())
Expand-Archive -LiteralPath $zip -DestinationPath $extract
$exe = Join-Path $extract 'WhatIf\WhatIf.exe'
$envBefore = $env:LOCALAPPDATA, $env:JAVA_HOME, $env:PATH
try {
    $env:LOCALAPPDATA = $demo
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -Wait
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -Wait
} finally {
    $env:LOCALAPPDATA, $env:JAVA_HOME, $env:PATH = $envBefore
}
```

Pada pembukaan pertama, buat mata kuliah uji dengan dua penilaian di kategori Umum dan gunakan Total poin. Simpan `49,5` dari `50` untuk Kuis dan biarkan Ujian dengan maksimum `100` tanpa skor. Di What If, target `82,1` atau `82.1` meminta `73,65` untuk Ujian. Coba `82,1.0` untuk validasi dan `115` untuk kebutuhan bonus. Keluarkan dan pulihkan baris, batalkan konfirmasi buang sekali, lalu keluar dengan konfirmasi. Pada pembukaan kedua, pastikan skor Kuis masih 49,5, Ujian kosong, dan What If kembali ke data aktual dengan target awal 85%.

Untuk penggunaan biasa, ekstrak seluruh ZIP dan buka `WhatIf.exe`. Simpan folder aplikasi dan runtime bersama launcher. Data aktual berada di `%LOCALAPPDATA%\WhatIfStandalone`.

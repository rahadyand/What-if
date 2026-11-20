#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'releases')
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gagal menjalankan $Program (kode $LASTEXITCODE)."
    }
}

if ($env:OS -ne 'Windows_NT') {
    throw 'Jalankan script ini pada Windows x64.'
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw 'Arahkan JAVA_HOME atau -JdkHome ke JDK 21 Windows x64.'
}
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$jdk = @{}
Get-Content -LiteralPath (Join-Path $JdkHome 'release') | ForEach-Object {
    if ($_ -match '^(?<key>[A-Z_]+)="(?<value>.*)"$') {
        $jdk[$Matches.key] = $Matches.value
    }
}
if ($jdk.OS_NAME -ne 'Windows' -or $jdk.OS_ARCH -notin @('amd64', 'x86_64') -or
    $jdk.JAVA_VERSION -notmatch '^21(?:[.+-]|$)') {
    throw 'Build memerlukan JDK 21 Windows x64.'
}

$root = Split-Path $PSScriptRoot -Parent
$OutputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputRoot)
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JdkHome
Push-Location -LiteralPath $root
try {
    [xml]$pom = Get-Content -LiteralPath 'pom.xml' -Raw
    $version = [string]$pom.project.version
    $mainJar = "$($pom.project.artifactId)-$version.jar"
    $mainClass = [string]($pom.project.build.plugins.plugin |
        Where-Object { $_.artifactId -eq 'javafx-maven-plugin' }).configuration.mainClass
    $commit = Invoke-Checked 'git' @('rev-parse', '--verify', 'HEAD')
    $workingTreeChanges = @(Invoke-Checked 'git' @('status', '--porcelain', '--untracked-files=normal')).Count -gt 0

    Invoke-Checked (Join-Path $root 'mvnw.cmd') @(
        '-B', 'clean', 'verify',
        'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies',
        '-DincludeScope=runtime'
    )

    $inputDir = (New-Item -ItemType Directory -Path 'target/app-image-input').FullName
    $fxDir = (New-Item -ItemType Directory -Path (Join-Path $inputDir 'javafx')).FullName
    Copy-Item -LiteralPath (Join-Path 'target' $mainJar) -Destination $inputDir
    $dependencies = @(Get-ChildItem -LiteralPath 'target/dependency' -Filter '*.jar' -File)
    $fxJars = @($dependencies | Where-Object { $_.Name -like 'javafx-*-win.jar' })
    $jdbc = $dependencies | Where-Object { $_.Name -like 'sqlite-jdbc-*.jar' }
    if ($fxJars.Count -eq 0 -or -not $jdbc) {
        throw 'Artefak JavaFX Windows atau SQLite JDBC tidak ditemukan.'
    }
    foreach ($jar in $fxJars) {
        Copy-Item -LiteralPath $jar.FullName -Destination $fxDir
    }
    foreach ($jar in ($dependencies | Where-Object { $_.Name -notlike 'javafx-*' })) {
        Copy-Item -LiteralPath $jar.FullName -Destination $inputDir
    }

    $appJars = @(Get-ChildItem -LiteralPath $inputDir -Filter '*.jar' -File)
    $jdepsArgs = @(
        '--multi-release', '21', '--ignore-missing-deps', '--recursive', '--list-deps',
        '--module-path', $fxDir, '--add-modules', 'javafx.controls'
    ) + @($appJars.FullName)
    $moduleNames = @(Invoke-Checked (Join-Path $JdkHome 'bin/jdeps.exe') $jdepsArgs |
        ForEach-Object { $_.Trim().Split('/')[0] })
    foreach ($jar in $fxJars) {
        $moduleNames += @(Invoke-Checked (Join-Path $JdkHome 'bin/jar.exe') @('--describe-module', '--file', $jar.FullName) |
            ForEach-Object {
                if ($_ -match '^requires (\S+)' -and $_ -notmatch '\bstatic\b') { $Matches[1] }
            })
    }
    $modules = ($moduleNames |
        Where-Object { $_ -match '^(java|jdk)\.[\w.]+$' } |
        Sort-Object -Unique) -join ','

    $stamp = [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss-fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    $release = (New-Item -ItemType Directory -Path (Join-Path $OutputRoot $stamp)).FullName
    Invoke-Checked (Join-Path $JdkHome 'bin/jpackage.exe') @(
        '--type', 'app-image', '--name', 'WhatIf', '--app-version', $version,
        '--dest', $release, '--input', $inputDir, '--main-jar', $mainJar, '--main-class', $mainClass,
        '--add-modules', $modules,
        '--java-options', '--module-path=$APPDIR/javafx',
        '--java-options', '--add-modules=javafx.controls'
    )

    $image = Join-Path $release 'WhatIf'
    $exe = Join-Path $image 'WhatIf.exe'
    $launcher = [IO.File]::ReadAllBytes($exe)
    $pe = [BitConverter]::ToInt32($launcher, 0x3C)
    if ([BitConverter]::ToUInt32($launcher, $pe) -ne 0x4550 -or
        [BitConverter]::ToUInt16($launcher, ($pe + 4)) -ne 0x8664 -or
        -not (Test-Path -LiteralPath (Join-Path $image 'runtime/bin/server/jvm.dll'))) {
        throw 'App-image Windows x64 tidak lengkap.'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $licenses = (New-Item -ItemType Directory -Path (Join-Path $image 'licenses')).FullName
    $fxVersion = [string]($pom.project.dependencies.dependency |
        Where-Object { $_.artifactId -eq 'javafx-controls' }).version
    Copy-Item -LiteralPath "docs/licenses/openjfx-$fxVersion" -Destination $licenses -Recurse
    Copy-Item -LiteralPath 'src/main/resources/fonts/OFL.txt' -Destination (Join-Path $licenses 'Merriweather-OFL.txt')
    $jdbcArchive = [IO.Compression.ZipFile]::OpenRead($jdbc.FullName)
    try {
        foreach ($name in @('LICENSE', 'LICENSE.zentus')) {
            $entry = $jdbcArchive.GetEntry("META-INF/maven/org.xerial/sqlite-jdbc/$name")
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $licenses "SQLite-JDBC-$name"))
        }
    } finally {
        $jdbcArchive.Dispose()
    }

    $payload = (@($appJars.Name) + @($fxJars.Name) | Sort-Object) -join ', '
    @"
What If $version | Windows x64 | app-image
Dibuat (UTC) = $stamp
Commit = $commit
Perubahan lokal saat build = $workingTreeChanges
JDK = $($jdk.IMPLEMENTOR) $($jdk.JAVA_RUNTIME_VERSION)
Modul runtime yang diminta = $modules
JAR aplikasi dan dependensi = $payload
Lisensi OpenJFX, SQLite JDBC, dan Merriweather tersedia di licenses/.
Lisensi JDK tersedia di runtime/legal/.
"@ | Set-Content -LiteralPath (Join-Path $image 'PROVENANCE.txt') -Encoding UTF8
    @'
Ekstrak seluruh ZIP. Buka WhatIf.exe dari dalam folder WhatIf.
Simpan folder app dan runtime bersama WhatIf.exe. Paket sudah memuat runtime Java.
Basis data aktual tersimpan di %LOCALAPPDATA%\WhatIfStandalone. Simulasi berlaku selama sesi.
'@ | Set-Content -LiteralPath (Join-Path $image 'BUKA.txt') -Encoding UTF8

    $manifest = @(Get-ChildItem -LiteralPath $image -Recurse -Force -File | Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($image.Length + 1).Replace('\', '/')
            "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() )  $relative"
        })
    $manifest | Set-Content -LiteralPath (Join-Path $image 'SHA256-APLIKASI.txt') -Encoding ASCII
    $zip = Join-Path $release "WhatIf-$version-windows-x64.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory($image, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
    $zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$zipHash  $(Split-Path $zip -Leaf)" |
        Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS.txt') -Encoding ASCII

    Write-Output "App-image: $image"
    Write-Output "ZIP: $zip"
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

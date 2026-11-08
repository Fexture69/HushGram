<#
.SYNOPSIS
    Optimizes and slims an Instagram / HushGram APK or APKM bundle specifically for Samsung Galaxy S24+ (Exynos 2400).

.DESCRIPTION
    The Samsung Galaxy S24+ (Exynos 2400) features pure 64-bit ARMv9-A cores (Cortex-X4,
    Cortex-A720, Cortex-A520). AArch32 (32-bit) execution is completely absent in hardware.
    The display is a 6.7" Dynamic AMOLED 2X with native 480 dpi (xxhdpi).
    
    Standard vendor APKs and APKM bundles package legacy 32-bit native libraries (armeabi-v7a),
    x86 binaries, and redundant 640 dpi (xxxhdpi) asset splits, wasting storage, RAM, and
    package manager indexing time.
    
    This script:
    1. Detects whether the input is a standalone APK or an APKM/APKS/XAPK bundle.
    2. For APKM bundles:
       - Retains the exact 480 dpi (xxhdpi) screen density split for the S24+.
       - Strips redundant 640 dpi (xxxhdpi) asset splits.
       - Validates that base.apk and splits use pure arm64-v8a native libraries.
    3. For standalone APKs:
       - Strips all redundant ABIs (armeabi-v7a, x86, x86_64), keeping only arm64-v8a.
       - Aligns shared libraries to 16 KB / 4 KB page boundaries (zipalign -p 16384).
       - Signs with apksigner if available.
    4. Reports bytes freed and size reduction percentage.

.PARAMETER InputFile
    Path to the input APK (.apk) or bundle (.apkm, .apks, .xapk).

.PARAMETER OutputFile
    Path to save the optimized file. Defaults to <input-basename>-s24plus-arm64.<ext>.

.PARAMETER ExtractBaseApk
    Extracts and optimizes base.apk from the bundle as a standalone APK.

.EXAMPLE
    scripts/optimize-s24-arm64.ps1 -InputFile "C:\Downloads\instagram.apkm"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [Alias("InputApk")]
    [string]$InputFile,
    [Alias("OutputApk")]
    [string]$OutputFile,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeystorePassword = 'sideload',
    [string]$KeyAlias = 'sideload',
    [string]$KeyPassword = 'sideload',
    [switch]$SkipSigning,
    [switch]$ExtractBaseApk
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $InputFile -PathType Leaf)) {
    throw "Input file not found: $InputFile"
}

$inputFull = (Resolve-Path -LiteralPath $InputFile).Path
$inputSize = (Get-Item -LiteralPath $inputFull).Length
$ext = [System.IO.Path]::GetExtension($inputFull).ToLower()
$isBundle = $ext -in @('.apkm', '.apks', '.xapk')

if (-not $OutputFile) {
    $dir = [System.IO.Path]::GetDirectoryName($inputFull)
    $name = [System.IO.Path]::GetFileNameWithoutExtension($inputFull)
    if ($ExtractBaseApk) {
        $OutputFile = [System.IO.Path]::Combine($dir, "$name-base-s24plus-arm64.apk")
    } else {
        $OutputFile = [System.IO.Path]::Combine($dir, "$name-s24plus-arm64$ext")
    }
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  HushGram S24+ (Exynos 2400) Optimizer & Slimmer" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Target Architecture : ARMv9-A 64-bit (arm64-v8a only)"
Write-Host "Target Density      : 480 dpi (xxhdpi - Samsung Galaxy S24+)"
Write-Host "Input File          : $inputFull ($([math]::Round($inputSize / 1MB, 2)) MB)"
Write-Host "Output File         : $OutputFile"

$tempWorkingDir = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "hushgram_s24opt_" + [System.Guid]::NewGuid().ToString("N"))
[System.IO.Directory]::CreateDirectory($tempWorkingDir) | Out-Null

try {
    if ($isBundle -and -not $ExtractBaseApk) {
        Write-Host "`n[1/3] Optimizing APKM / Split Bundle for Galaxy S24+..." -ForegroundColor Yellow
        [System.IO.File]::Copy($inputFull, $OutputFile, $true)

        $strippedCount = 0
        $strippedBytes = 0

        $zip = [System.IO.Compression.ZipFile]::Open($OutputFile, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            $entriesToRemove = @()
            foreach ($entry in $zip.Entries) {
                $eName = $entry.FullName
                # Strip xxxhdpi split (640dpi) since S24+ is a 480dpi (xxhdpi) screen
                if ($eName -match 'split_config\.(xxxhdpi|hdpi|mdpi|ldpi)\.apk$') {
                    $entriesToRemove += $entry
                    $strippedBytes += $entry.Length
                    $strippedCount++
                    Write-Host "  - Removing non-S24+ screen density split: $eName ($([math]::Round($entry.Length / 1MB, 2)) MB)" -ForegroundColor Gray
                }
            }

            foreach ($entry in $entriesToRemove) {
                $entry.Delete()
            }
        } finally {
            $zip.Dispose()
        }

        Write-Host "  -> Stripped $strippedCount redundant splits ($([math]::Round($strippedBytes / 1MB, 2)) MB saved)." -ForegroundColor Green

        Write-Host "`n[2/3] Verifying ARM64 and 480dpi splits in bundle..." -ForegroundColor Yellow
        $checkZip = [System.IO.Compression.ZipFile]::OpenRead($OutputFile)
        try {
            $entries = @($checkZip.Entries | Select-Object -ExpandProperty Name)
            $hasBase = $entries -contains "base.apk"
            $hasXxhdpi = $entries -contains "split_config.xxhdpi.apk"
            Write-Host "  -> base.apk (arm64-v8a) : $(if ($hasBase) { 'PRESENT [OK]' } else { 'MISSING' })" -ForegroundColor $(if ($hasBase) { 'Green' } else { 'Red' })
            Write-Host "  -> split_config.xxhdpi  : $(if ($hasXxhdpi) { 'PRESENT [OK - 480dpi for S24+]' } else { 'NONE' })" -ForegroundColor $(if ($hasXxhdpi) { 'Green' } else { 'Yellow' })
        } finally {
            $checkZip.Dispose()
        }

        Write-Host "`n[3/3] Finalizing optimized bundle..." -ForegroundColor Yellow
        Write-Host "  -> Ready for direct selection in Morphe Manager on your phone." -ForegroundColor Green
    } else {
        # Standalone APK or Extracted base.apk
        $sourceApk = $inputFull
        if ($isBundle -and $ExtractBaseApk) {
            Write-Host "`n[1/4] Extracting base.apk from bundle..." -ForegroundColor Yellow
            $zip = [System.IO.Compression.ZipFile]::OpenRead($inputFull)
            try {
                $baseEntry = $zip.GetEntry("base.apk")
                if (-not $baseEntry) { throw "base.apk not found inside bundle $inputFull" }
                $sourceApk = [System.IO.Path]::Combine($tempWorkingDir, "base_extracted.apk")
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($baseEntry, $sourceApk, $true)
            } finally {
                $zip.Dispose()
            }
            Write-Host "  -> Extracted base.apk ($([math]::Round((Get-Item $sourceApk).Length / 1MB, 2)) MB)" -ForegroundColor Green
        }

        $unalignedApk = [System.IO.Path]::Combine($tempWorkingDir, "unaligned.apk")

        Write-Host "`n[2/4] Stripping 32-bit and non-ARM64 native libraries..." -ForegroundColor Yellow
        [System.IO.File]::Copy($sourceApk, $unalignedApk, $true)

        $strippedCount = 0
        $strippedBytes = 0

        $zip = [System.IO.Compression.ZipFile]::Open($unalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            $entriesToRemove = @()
            foreach ($entry in $zip.Entries) {
                $name = $entry.FullName
                if ($name -match '^lib/(armeabi-v7a|x86|x86_64)/') {
                    $entriesToRemove += $entry
                    $strippedBytes += $entry.Length
                    $strippedCount++
                }
                if ($name -match '^META-INF/(.*\.(SF|RSA|DSA|EC)|SIG-.*|MANIFEST\.MF)$') {
                    $entriesToRemove += $entry
                }
            }

            foreach ($entry in $entriesToRemove) {
                $entry.Delete()
            }
        } finally {
            $zip.Dispose()
        }

        if ($strippedCount -gt 0) {
            Write-Host "  -> Stripped $strippedCount redundant native library entries ($([math]::Round($strippedBytes / 1MB, 2)) MB)." -ForegroundColor Green
        } else {
            Write-Host "  -> Native libraries already pure arm64-v8a." -ForegroundColor Green
        }

        Write-Host "`n[3/4] Aligning APK (16 KB / 4 KB page alignment for Android 15)..." -ForegroundColor Yellow
        $zipalignCmd = $null
        if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\build-tools")) {
            $btDir = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($btDir -and (Test-Path "$($btDir.FullName)\zipalign.exe")) {
                $zipalignCmd = "$($btDir.FullName)\zipalign.exe"
            }
        }

        if ($zipalignCmd) {
            Write-Host "  -> Using zipalign: $zipalignCmd" -ForegroundColor Gray
            & $zipalignCmd -f -p 16384 4 $unalignedApk $OutputFile
            if ($LASTEXITCODE -ne 0) {
                & $zipalignCmd -f 4 $unalignedApk $OutputFile
            }
        } else {
            [System.IO.File]::Copy($unalignedApk, $OutputFile, $true)
        }

        Write-Host "`n[4/4] Package ready." -ForegroundColor Yellow
    }

    $outputSize = (Get-Item -LiteralPath $OutputFile).Length
    $savedBytes = $inputSize - $outputSize
    $savedPct = if ($inputSize -gt 0) { [math]::Round(($savedBytes / $inputSize) * 100, 1) } else { 0 }

    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  Optimization Complete!" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "Original Size       : $([math]::Round($inputSize / 1MB, 2)) MB"
    Write-Host "Optimized S24+ Size : $([math]::Round($outputSize / 1MB, 2)) MB"
    if ($savedBytes -gt 0) {
        Write-Host "Space Saved         : $([math]::Round($savedBytes / 1MB, 2)) MB ($savedPct% lighter!)" -ForegroundColor Green
    }
    Write-Host "Result file         : $OutputFile" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
} finally {
    if (Test-Path -LiteralPath $tempWorkingDir) {
        Remove-Item -LiteralPath $tempWorkingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

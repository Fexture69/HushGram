<#
.SYNOPSIS
    Optimizes and slims an Instagram / HushGram APK specifically for Samsung Galaxy S24+ (Exynos 2400).

.DESCRIPTION
    The Samsung Galaxy S24+ (Exynos 2400) features pure 64-bit ARMv9-A cores (Cortex-X4,
    Cortex-A720, Cortex-A520). AArch32 (32-bit) execution is completely absent in hardware.
    
    Standard vendor APKs package legacy 32-bit native libraries (armeabi-v7a) and x86/x86_64
    binaries alongside arm64-v8a, wasting ~40-50 MB of storage, RAM, and package manager indexing time.
    
    This script:
    1. Extracts the APK and strips all redundant ABIs (armeabi-v7a, x86, x86_64), keeping only arm64-v8a.
    2. Aligns shared libraries to 16 KB / 4 KB page boundaries (zipalign -p 16384) for Android 15
       modern kernel compatibility on ARMv9.
    3. Re-signs the optimized APK with apksigner using the sideload or specified keystore.
    4. Reports bytes freed and size reduction percentage.

.PARAMETER InputApk
    Path to the input APK (.apk).

.PARAMETER OutputApk
    Path to save the optimized APK. Defaults to <input-basename>-s24plus-arm64.apk.

.PARAMETER Keystore
    Path to the keystore for signing. Defaults to $HOME\.android\sideload-release.jks.

.PARAMETER KeystorePassword
    Password for the keystore. Defaults to 'sideload'.

.PARAMETER KeyAlias
    Alias of the signing key. Defaults to 'sideload'.

.PARAMETER KeyPassword
    Password for the key. Defaults to 'sideload'.

.EXAMPLE
    scripts/optimize-s24-arm64.ps1 -InputApk "C:\builds\instagram-454.0.0.54.77.apk"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputApk,
    [string]$OutputApk,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeystorePassword = 'sideload',
    [string]$KeyAlias = 'sideload',
    [string]$KeyPassword = 'sideload',
    [switch]$SkipSigning
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $InputApk -PathType Leaf)) {
    throw "Input APK not found: $InputApk"
}

$inputFull = (Resolve-Path -LiteralPath $InputApk).Path
$inputSize = (Get-Item -LiteralPath $inputFull).Length

if (-not $OutputApk) {
    $dir = [System.IO.Path]::GetDirectoryName($inputFull)
    $name = [System.IO.Path]::GetFileNameWithoutExtension($inputFull)
    $OutputApk = [System.IO.Path]::Combine($dir, "$name-s24plus-arm64.apk")
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  HushGram S24+ (Exynos 2400) APK Lightweight Optimizer" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Target Architecture : ARMv9-A 64-bit (arm64-v8a only)"
Write-Host "Target Device       : Samsung Galaxy S24+ (Exynos 2400 / Xclipse 940)"
Write-Host "Input APK           : $inputFull ($([math]::Round($inputSize / 1MB, 2)) MB)"
Write-Host "Output APK          : $OutputApk"

$tempWorkingDir = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "hushgram_s24opt_" + [System.Guid]::NewGuid().ToString("N"))
[System.IO.Directory]::CreateDirectory($tempWorkingDir) | Out-Null

try {
    $unalignedApk = [System.IO.Path]::Combine($tempWorkingDir, "unaligned.apk")

    Write-Host "`n[1/4] Stripping 32-bit and non-ARM64 native libraries..." -ForegroundColor Yellow
    
    # Copy input to unaligned destination
    [System.IO.File]::Copy($inputFull, $unalignedApk, $true)

    $strippedCount = 0
    $strippedBytes = 0

    $zip = [System.IO.Compression.ZipFile]::Open($unalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entriesToRemove = @()
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            # Strip 32-bit ARM (armeabi-v7a) and x86/x86_64 libraries
            if ($name -match '^lib/(armeabi-v7a|x86|x86_64)/') {
                $entriesToRemove += $entry
                $strippedBytes += $entry.Length
                $strippedCount++
            }
            # Remove META-INF signatures since we are re-signing
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

    Write-Host "  -> Stripped $strippedCount redundant native library entries ($([math]::Round($strippedBytes / 1MB, 2)) MB uncompressed)." -ForegroundColor Green

    Write-Host "`n[2/4] Verifying retained arm64-v8a architecture..." -ForegroundColor Yellow
    $checkZip = [System.IO.Compression.ZipFile]::OpenRead($unalignedApk)
    try {
        $hasArm64 = $false
        foreach ($entry in $checkZip.Entries) {
            if ($entry.FullName -match '^lib/arm64-v8a/') {
                $hasArm64 = $true
                break
            }
        }
        if (-not $hasArm64) {
            Write-Warning "No lib/arm64-v8a libraries found in source APK! Verify input package."
        } else {
            Write-Host "  -> Verified: Pure arm64-v8a native libraries preserved." -ForegroundColor Green
        }
    } finally {
        $checkZip.Dispose()
    }

    Write-Host "`n[3/4] Aligning APK (16 KB / 4 KB page alignment for Android 15)..." -ForegroundColor Yellow
    # Look for zipalign in Android SDK if available
    $zipalignCmd = $null
    if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\build-tools")) {
        $btDir = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($btDir -and (Test-Path "$($btDir.FullName)\zipalign.exe")) {
            $zipalignCmd = "$($btDir.FullName)\zipalign.exe"
        }
    }

    if ($zipalignCmd) {
        Write-Host "  -> Using zipalign: $zipalignCmd (16KB alignment)" -ForegroundColor Gray
        & $zipalignCmd -f -p 16384 4 $unalignedApk $OutputApk
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  -> Falling back to 4-byte zipalign..." -ForegroundColor Yellow
            & $zipalignCmd -f 4 $unalignedApk $OutputApk
        }
    } else {
        Write-Host "  -> zipalign not found in path; copying unaligned APK (run zipalign before release)." -ForegroundColor DarkYellow
        [System.IO.File]::Copy($unalignedApk, $OutputApk, $true)
    }

    if (-not $SkipSigning) {
        Write-Host "`n[4/4] Signing optimized APK..." -ForegroundColor Yellow
        # Find apksigner
        $apksignerCmd = $null
        if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\build-tools")) {
            $btDir = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($btDir -and (Test-Path "$($btDir.FullName)\apksigner.bat")) {
                $apksignerCmd = "$($btDir.FullName)\apksigner.bat"
            }
        }

        if ($apksignerCmd -and (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
            Write-Host "  -> Signing with: $apksignerCmd" -ForegroundColor Gray
            & $apksignerCmd sign --ks $Keystore --ks-pass "pass:$KeystorePassword" --ks-key-alias $KeyAlias --key-pass "pass:$KeyPassword" $OutputApk
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  -> Successfully signed with sideload keystore." -ForegroundColor Green
            } else {
                Write-Warning "Signing exited with code $LASTEXITCODE. You may re-sign using Morphe Manager or apksigner."
            }
        } else {
            Write-Host "  -> Skipped signing (keystore not found at $Keystore or apksigner unavailable)." -ForegroundColor DarkYellow
            Write-Host "     Sign using Morphe Manager or your personal release key." -ForegroundColor DarkYellow
        }
    } else {
        Write-Host "`n[4/4] Skipped signing (-SkipSigning requested)." -ForegroundColor Gray
    }

    $outputSize = (Get-Item -LiteralPath $OutputApk).Length
    $savedBytes = $inputSize - $outputSize
    $savedPct = if ($inputSize -gt 0) { [math]::Round(($savedBytes / $inputSize) * 100, 1) } else { 0 }

    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  Optimization Complete!" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "Original Size       : $([math]::Round($inputSize / 1MB, 2)) MB"
    Write-Host "Optimized S24+ Size : $([math]::Round($outputSize / 1MB, 2)) MB"
    Write-Host "Space Saved         : $([math]::Round($savedBytes / 1MB, 2)) MB ($savedPct% lighter!)" -ForegroundColor Green
    Write-Host "Result file         : $OutputApk" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
} finally {
    if (Test-Path -LiteralPath $tempWorkingDir) {
        Remove-Item -LiteralPath $tempWorkingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

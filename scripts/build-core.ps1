# Builds the Rust core for Android and copies libbetteryts_core.so into the app.
# Needs: rustup targets aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android;
# cargo-ndk (cargo install cargo-ndk); the Android NDK (ANDROID_NDK_HOME or the SDK's ndk folder).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $env:ANDROID_NDK_HOME) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $ndk = Get-ChildItem (Join-Path $sdk "ndk") -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
    if (-not $ndk) { throw "Android NDK not found. Install it from Android Studio's SDK Manager or set ANDROID_NDK_HOME." }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}

Push-Location (Join-Path $root "core")
try {
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o ../android/app/src/main/jniLibs build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed" }
} finally {
    Pop-Location
}
Write-Host "Core built into android/app/src/main/jniLibs"

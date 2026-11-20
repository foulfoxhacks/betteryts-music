# Builds a release and publishes it for the in-app updater (update/Updater.kt).
#
#   1. Builds the Rust core, the sideload APK and the Play bundle (signed with keystore.properties).
#   2. Copies the APK to ../betteryts/public/app/ and writes ../betteryts/public/app/update.json
#      with its version, size, SHA-256 and signing-certificate fingerprint.
#   3. Copies both files to ../releases/.
# Then deploy the site (cd ../betteryts; npx cf deploy) and installed apps update themselves.
# APKs are gitignored in the site repo (they're build output); update.json is committed.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$site = Join-Path (Split-Path -Parent $root) "betteryts\public\app"
$releases = Join-Path (Split-Path -Parent $root) "releases"

& (Join-Path $PSScriptRoot "build-core.ps1")

$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$android\.gradle\uds"
Push-Location $android
try {
    & .\gradlew.bat :app:assembleSideloadRelease :app:bundlePlayRelease -q
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

$gradle = Get-Content (Join-Path $android "app\build.gradle.kts") -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$apk = Join-Path $android "app\build\outputs\apk\sideload\release\app-sideload-release.apk"
$aab = Join-Path $android "app\build\outputs\bundle\playRelease\app-play-release.aab"

$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
$certs = & (Join-Path $buildTools.FullName "apksigner.bat") verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "The APK isn't validly signed" }
$signer = ([regex]::Match(($certs -join "`n"), 'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})')).Groups[1].Value
if (-not $signer) { throw "Couldn't read the signing certificate" }

$file = "BetterYTS-Music-$name.apk"
New-Item -ItemType Directory -Force $site | Out-Null
New-Item -ItemType Directory -Force $releases | Out-Null
Get-ChildItem $site -Filter "BetterYTS-Music-*.apk" | Where-Object Name -ne $file | Remove-Item
Copy-Item $apk (Join-Path $site $file) -Force
Copy-Item $apk (Join-Path $releases $file) -Force
Copy-Item $aab (Join-Path $releases "BetterYTS-Music-$name.aab") -Force

$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apk).Length
$notes = if ($env:BTM_NOTES) { $env:BTM_NOTES } else { "" }
$manifest = [ordered]@{
    packageName  = "dev.foulfoxhacks.betteryts.music"
    versionCode  = $code
    versionName  = $name
    notes        = $notes
    released     = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    signerSha256 = $signer
    apk          = [ordered]@{ url = "https://yts.foulfoxhacks.dev/app/$file"; size = $size; sha256 = $sha }
}
[System.IO.File]::WriteAllText((Join-Path $site "update.json"), ($manifest | ConvertTo-Json -Depth 4) + "`n")
Write-Host "Published $name ($code): $file, $size bytes, sha256 $sha"
Write-Host "Next: cd ..\betteryts; npx cf deploy"

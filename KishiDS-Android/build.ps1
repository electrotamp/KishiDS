<#
.SYNOPSIS
  Builds KishiDS.apk with the plain Android SDK command-line tools: no Gradle, no Android Studio, no downloaded libraries.

.DESCRIPTION
  aapt2 (resources + manifest) -> javac (Java 8 language level) -> d8 (dex) -> zipalign -> apksigner.
  The firmware image (firmware-research\ds4-firmware\kishi_ds4.bin) is packaged into the app, so rebuild after every firmware build.
  Needs JDK 17 and the Android SDK (platform android-34 and build-tools 34): ANDROID_HOME / ANDROID_SDK_ROOT, or E:\Android\Sdk.

  The first run creates a local signing key in keystore\ (never commit it).  For your own release key pass -Keystore / -KeyAlias / -KeyPass.

.EXAMPLE
  .\build.ps1                 # builds dist\KishiDS.apk
  .\build.ps1 -Install        # also installs it on the connected phone with adb
#>
param(
    [string]$Keystore = "$PSScriptRoot\keystore\kishids.keystore",
    [string]$KeyAlias = "kishids",
    [string]$KeyPass = "kishids-local",
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,
    [switch]$Install
)

$ErrorActionPreference = "Stop"

function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { Fail "$([IO.Path]::GetFileName($exe)) failed ($LASTEXITCODE)" }
}

# ---- locate the tools ----
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "E:\Android\Sdk", "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { Fail "Android SDK not found. Set ANDROID_HOME." }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d\.].*$', '') } -Descending | Select-Object -First 1
if (-not $bt) { Fail "No build-tools in $sdk\build-tools" }
$platform = Get-ChildItem "$sdk\platforms" -Directory | Where-Object { Test-Path "$($_.FullName)\android.jar" } | Sort-Object { [int]($_.Name -replace '\D', '') } -Descending | Select-Object -First 1
if (-not $platform) { Fail "No platform with android.jar in $sdk\platforms" }
$androidJar = "$($platform.FullName)\android.jar"
$aapt2 = "$($bt.FullName)\aapt2.exe"; $aapt = "$($bt.FullName)\aapt.exe"; $d8 = "$($bt.FullName)\d8.bat"
$zipalign = "$($bt.FullName)\zipalign.exe"; $apksigner = "$($bt.FullName)\apksigner.bat"
$javaHome = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME } else { $null }
$javac = if ($javaHome) { "$javaHome\bin\javac.exe" } else { (Get-Command javac -ErrorAction Stop).Source }
$keytool = if ($javaHome) { "$javaHome\bin\keytool.exe" } else { (Get-Command keytool -ErrorAction Stop).Source }
Write-Host "SDK $sdk | build-tools $($bt.Name) | $($platform.Name)"

$main = "$PSScriptRoot\app\src\main"
$build = "$PSScriptRoot\build"
$dist = "$PSScriptRoot\dist"
$fw = (Resolve-Path "$PSScriptRoot\..\firmware-research\ds4-firmware\kishi_ds4.bin" -ErrorAction SilentlyContinue)
if (-not $fw) { Fail "Firmware image not found: firmware-research\ds4-firmware\kishi_ds4.bin (build the firmware first)" }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force "$build\gen", "$build\classes", "$build\dex", "$build\extra-assets", $dist | Out-Null
Copy-Item $fw.Path "$build\extra-assets\kishi_ds4.bin"

# ---- resources, manifest, R.java ----
Write-Host "aapt2 compile + link"
Run $aapt2 @("compile", "--dir", "$main\res", "-o", "$build\res.zip")
Run $aapt2 @("link", "-o", "$build\base.apk", "-I", $androidJar, "--manifest", "$main\AndroidManifest.xml",
    "--min-sdk-version", "24", "--target-sdk-version", "34", "--version-code", "$VersionCode", "--version-name", $VersionName,
    "-A", "$main\assets", "-A", "$build\extra-assets", "--java", "$build\gen", "--auto-add-overlay", "$build\res.zip")

# ---- java -> class -> dex ----
Write-Host "javac"
$sources = @(Get-ChildItem "$main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }) + @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$list = "$build\sources.txt"
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $list -Encoding ASCII
Run $javac @("--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-classpath", $androidJar, "-d", "$build\classes", "@$list")

Write-Host "d8"
$jar = if ($javaHome) { "$javaHome\bin\jar.exe" } else { (Get-Command jar -ErrorAction Stop).Source }
Run $jar @("--create", "--file", "$build\classes.jar", "-C", "$build\classes", ".")
Run $d8 @("--lib", $androidJar, "--min-api", "24", "--output", "$build\dex", "$build\classes.jar")

# ---- package, align, sign ----
Write-Host "package"
Push-Location "$build\dex"
Run $aapt @("add", "$build\base.apk", "classes.dex")
Pop-Location
Run $zipalign @("-f", "-p", "4", "$build\base.apk", "$build\aligned.apk")

if (-not (Test-Path $Keystore)) {
    Write-Host "creating a local signing key at $Keystore"
    New-Item -ItemType Directory -Force (Split-Path $Keystore) | Out-Null
    Run $keytool @("-genkeypair", "-keystore", $Keystore, "-alias", $KeyAlias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", $KeyPass, "-keypass", $KeyPass, "-dname", "CN=KishiDS local build")
}
$apk = "$dist\KishiDS.apk"
Run $apksigner @("sign", "--ks", $Keystore, "--ks-key-alias", $KeyAlias, "--ks-pass", "pass:$KeyPass", "--key-pass", "pass:$KeyPass", "--out", $apk, "$build\aligned.apk")
Run $apksigner @("verify", $apk)

$size = [math]::Round((Get-Item $apk).Length / 1KB)
Write-Host "Built $apk ($size KB)" -ForegroundColor Green

if ($Install) {
    $adb = "$sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
    Run $adb @("install", "-r", $apk)
}

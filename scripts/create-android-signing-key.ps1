<#
  Creates the Android release signing key for FamilyCircle (run ONCE, by the project owner).

  - Asks for a password (hidden input); nothing is written to disk except the keystore itself.
  - Puts the base64 value for the GitHub secret ANDROID_KEYSTORE_BASE64 on the clipboard.
  - The keystore must be BACKED UP (e.g. password manager + offline copy). If it is lost,
    existing installations can never receive app updates again.

  Usage (PowerShell):
    powershell -ExecutionPolicy Bypass -File scripts\create-android-signing-key.ps1
#>
param(
    [string]$OutDir = (Join-Path $env:USERPROFILE "FamilyCircle-Signing"),
    [string]$Keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
)
$ErrorActionPreference = "Stop"
$keystore = Join-Path $OutDir "familycircle-release.jks"

if (Test-Path $keystore) { throw "Keystore already exists: $keystore - not overwriting." }
if (-not (Test-Path $Keytool)) { throw "keytool not found at $Keytool (pass -Keytool <path>)." }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$p1 = Read-Host "New keystore password (min. 12 characters)" -AsSecureString
$p2 = Read-Host "Repeat password" -AsSecureString
$plain1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1))
$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2))
if ($plain1 -ne $plain2) { throw "Passwords do not match." }
if ($plain1.Length -lt 12) { throw "Password too short." }

# PKCS12: store and key password are the same.
& $Keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias familycircle `
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=FamilyCircle, O=PurePixel, C=CH" `
    -storepass $plain1 -keypass $plain1
if ($LASTEXITCODE -ne 0) { throw "keytool failed." }
$plain1 = $null; $plain2 = $null

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | Set-Clipboard
Write-Host ""
Write-Host "Keystore created: $keystore"
Write-Host "Base64 value is on the clipboard."
Write-Host ""
Write-Host "Now add these repository secrets on GitHub:"
Write-Host "  https://github.com/PurepixelDEV/familycircle/settings/secrets/actions/new"
Write-Host "  ANDROID_KEYSTORE_BASE64   = (paste from clipboard)"
Write-Host "  ANDROID_KEYSTORE_PASSWORD = your password"
Write-Host "  ANDROID_KEY_PASSWORD      = the same password"
Write-Host "  ANDROID_KEY_ALIAS         = familycircle"
Write-Host ""
Write-Host "Then BACK UP the keystore file and the password (password manager + offline copy)."

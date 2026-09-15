[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ApplicationId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Alias = 'duolauncher-release',

    [switch]$ShowSecrets
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($ApplicationId -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
    throw 'ApplicationId must be a valid Android application ID with at least two dot-separated segments.'
}
if ($ApplicationId -eq 'com.jake.duolauncher') {
    throw 'Choose a fork application ID; the upstream application ID is not valid for this signed fork release.'
}

function Get-ReleasePassword {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Protect-PrivatePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ($IsWindows) {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        # Set-Acl can try to preserve a SACL and require SeSecurityPrivilege. icacls changes
        # only the discretionary ACL, which is the protection this private output needs.
        $icacls = Get-Command icacls.exe -ErrorAction Stop
        $icaclsPath = $icacls.Source
        & $icaclsPath $Path '/inheritance:r' '/grant:r' "${identity}:(F)" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not restrict access to private release material: $Path"
        }
    }
}

$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $outputPath)) {
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
}
if (-not (Test-Path -LiteralPath $outputPath -PathType Container)) {
    throw "OutputDirectory is not a directory: $outputPath"
}
Protect-PrivatePath -Path $outputPath

$keystorePath = Join-Path $outputPath 'duolauncher-release.p12'
$base64Path = Join-Path $outputPath 'duolauncher-release.base64.txt'
$secretsPath = Join-Path $outputPath 'duolauncher-release-github-secrets.txt'
foreach ($path in @($keystorePath, $base64Path, $secretsPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing private release material: $path"
    }
}

$keytoolCandidates = @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' }),
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool' }),
    'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
    'C:\Program Files\Android\Android Studio\jre\bin\keytool.exe',
    'keytool.exe',
    'keytool'
) | Where-Object { $_ }
$keytool = $null
foreach ($candidate in $keytoolCandidates) {
    if ([System.IO.Path]::IsPathRooted($candidate) -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        $keytool = $candidate
        break
    }
    try {
        $command = Get-Command $candidate -ErrorAction Stop
        $keytool = $command.Source
        break
    } catch {
        # Try the next candidate without exposing filesystem details.
    }
}
if (-not $keytool) {
    throw 'keytool was not found. Install JDK 17 or set JAVA_HOME to a JDK directory.'
}

$password = Get-ReleasePassword
$distinguishedName = "CN=Duo Launcher Release, O=$ApplicationId"
& $keytool -genkeypair -v `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -storepass $password `
    -keypass $password `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname $distinguishedName `
    -noprompt
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw 'keytool did not create the PKCS12 keystore.'
}

Protect-PrivatePath -Path $keystorePath
$base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystorePath))
[System.IO.File]::WriteAllText($base64Path, $base64, [System.Text.Encoding]::ASCII)
Protect-PrivatePath -Path $base64Path

$githubValues = @"
DUO_APPLICATION_ID=$ApplicationId
DUO_RELEASE_KEYSTORE_BASE64=$base64
DUO_RELEASE_STORE_PASSWORD=$password
DUO_RELEASE_KEY_ALIAS=$Alias
DUO_RELEASE_KEY_PASSWORD=$password
"@
[System.IO.File]::WriteAllText($secretsPath, $githubValues.TrimEnd() + [Environment]::NewLine, [System.Text.Encoding]::UTF8)
Protect-PrivatePath -Path $secretsPath

Write-Host ''
Write-Host 'Created permanent DuoLauncher signing material.' -ForegroundColor Green
Write-Host "Private directory: $outputPath"
Write-Host "Keystore:          $keystorePath"
Write-Host "Base64 file:       $base64Path"
Write-Host "GitHub values:     $secretsPath"
Write-Host ''
Write-Host 'Add DUO_APPLICATION_ID as a repository Actions variable.'
Write-Host 'Add the other four values as android-release environment secrets.'
Write-Host 'Back up the keystore, alias, and passwords separately. Do not commit this directory.' -ForegroundColor Yellow
if ($ShowSecrets) {
    Write-Host ''
    Write-Host 'GitHub values (copy locally; do not paste them into chat):' -ForegroundColor Yellow
    Get-Content -LiteralPath $secretsPath
}

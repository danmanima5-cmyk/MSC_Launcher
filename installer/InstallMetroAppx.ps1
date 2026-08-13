$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdministrator = $principal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdministrator) {
    $arguments = @(
        '-NoProfile'
        '-ExecutionPolicy', 'Bypass'
        '-File', ('"{0}"' -f $PSCommandPath)
    )
    $elevated = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList $arguments `
        -Verb RunAs `
        -Wait `
        -PassThru
    exit $elevated.ExitCode
}

$certificatePath = Join-Path $PSScriptRoot 'MSC-Launcher-Tiles.cer'
$appxFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'MSC-Launcher-Tiles-*-sideload.appx' -File)
$appxPath = if ($appxFiles.Count -eq 1) { $appxFiles[0].FullName } else { '' }

if (-not (Test-Path -LiteralPath $certificatePath) -or -not (Test-Path -LiteralPath $appxPath)) {
    throw 'The certificate or APPX file is missing next to the installer script.'
}

$certificate = Get-PfxCertificate -FilePath $certificatePath
if ($certificate.Subject -ne 'CN=Meine Starten Corporation') {
    throw "Unexpected certificate publisher: $($certificate.Subject)"
}

$signature = Get-AuthenticodeSignature -FilePath $appxPath
if ($null -eq $signature.SignerCertificate) {
    throw 'The APPX package has no digital signature.'
}
if ($signature.SignerCertificate.Thumbprint -ne $certificate.Thumbprint) {
    throw 'The bundled certificate does not match the APPX signature.'
}

Import-Certificate -FilePath $certificatePath `
    -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null

$trusted = Get-ChildItem 'Cert:\LocalMachine\TrustedPeople' |
    Where-Object { $_.Thumbprint -eq $certificate.Thumbprint } |
    Select-Object -First 1
if ($null -eq $trusted) {
    throw 'The certificate could not be added to LocalMachine\TrustedPeople.'
}

Add-AppxPackage -Path $appxPath
Write-Host 'MSC Launcher Tiles was installed successfully.' -ForegroundColor Green

param(
    [Parameter(Mandatory = $true)][string]$InputFile,
    [string]$PfxFile = $env:MSC_CODESIGN_PFX,
    [string]$CertificateThumbprint = $env:MSC_CODESIGN_SHA1,
    [string]$TimestampUrl = 'http://timestamp.digicert.com',
    [string]$SignTool
)

$ErrorActionPreference = 'Stop'
$inputPath = [IO.Path]::GetFullPath($InputFile)
if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) {
    throw "Installer not found: $inputPath"
}

if ([string]::IsNullOrWhiteSpace($SignTool)) {
    $fromPath = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) {
        $SignTool = $fromPath.Source
    } else {
        $sdkRoots = @(
            "$env:ProgramFiles(x86)\Windows Kits\10\bin",
            "$env:ProgramFiles\Windows Kits\10\bin"
        ) | Where-Object { Test-Path -LiteralPath $_ }
        $SignTool = $sdkRoots |
            ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter signtool.exe -Recurse -File -ErrorAction SilentlyContinue } |
            Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if ([string]::IsNullOrWhiteSpace($SignTool) -or -not (Test-Path -LiteralPath $SignTool)) {
    throw 'signtool.exe was not found. Install the Windows SDK or pass -SignTool.'
}

$signArguments = @('sign', '/fd', 'SHA256', '/tr', $TimestampUrl, '/td', 'SHA256',
    '/d', 'MSC Launcher by Meine Starten Corporation',
    '/du', 'https://github.com/danmanima5-cmyk/MSC_Launcher')
if (-not [string]::IsNullOrWhiteSpace($CertificateThumbprint)) {
    $signArguments += @('/sha1', ($CertificateThumbprint -replace '\s', ''))
} elseif (-not [string]::IsNullOrWhiteSpace($PfxFile)) {
    $pfxPath = [IO.Path]::GetFullPath($PfxFile)
    if (-not (Test-Path -LiteralPath $pfxPath -PathType Leaf)) {
        throw "PFX file not found: $pfxPath"
    }
    if ([string]::IsNullOrEmpty($env:MSC_CODESIGN_PASSWORD)) {
        throw 'Set MSC_CODESIGN_PASSWORD for the PFX certificate.'
    }
    $signArguments += @('/f', $pfxPath, '/p', $env:MSC_CODESIGN_PASSWORD)
} else {
    throw 'Set MSC_CODESIGN_SHA1 (certificate store) or MSC_CODESIGN_PFX (PFX file).'
}
$signArguments += $inputPath

& $SignTool @signArguments
if ($LASTEXITCODE -ne 0) {
    throw "SignTool signing failed with exit code $LASTEXITCODE."
}

& $SignTool verify /pa /all /tw /v $inputPath
if ($LASTEXITCODE -ne 0) {
    throw "Authenticode verification failed with exit code $LASTEXITCODE."
}

Write-Host "Signed and verified: $inputPath"

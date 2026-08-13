param(
    [Parameter(Mandatory = $true)][string]$InputAppx,
    [Parameter(Mandatory = $true)][string]$OutputAppx,
    [Parameter(Mandatory = $true)][string]$CertificateFile,
    [string]$SignTool = "C:\Program Files (x86)\Windows Kits\10\App Certification Kit\signtool.exe"
)

$ErrorActionPreference = 'Stop'
$publisher = 'CN=Meine Starten Corporation'
$inputPath = [IO.Path]::GetFullPath($InputAppx)
$outputPath = [IO.Path]::GetFullPath($OutputAppx)
$certificatePath = [IO.Path]::GetFullPath($CertificateFile)

[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
Copy-Item -LiteralPath $inputPath -Destination $outputPath -Force

$friendlyName = 'Meine Starten Corporation MSC Launcher certificate'
$certificate = Get-ChildItem 'Cert:\CurrentUser\My' |
    Where-Object {
        $_.Subject -eq $publisher -and
        $_.FriendlyName -eq $friendlyName -and
        $_.HasPrivateKey -and
        $_.NotAfter -gt (Get-Date).AddDays(30)
    } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1

if ($null -eq $certificate) {
    $certificate = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $publisher `
        -FriendlyName $friendlyName `
        -CertStoreLocation 'Cert:\CurrentUser\My'
}

Export-Certificate -Cert $certificate -FilePath $certificatePath -Force | Out-Null
& $SignTool sign /fd SHA256 /sha1 $certificate.Thumbprint $outputPath
if ($LASTEXITCODE -ne 0) {
    throw "SignTool failed with exit code $LASTEXITCODE"
}

Write-Host "Signed with persistent certificate: $($certificate.Thumbprint)"

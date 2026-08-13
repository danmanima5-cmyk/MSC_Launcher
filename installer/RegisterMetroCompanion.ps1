$ErrorActionPreference = 'Stop'

$jarFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'lib') `
    -Filter 'MSC-Launcher-Metro-*.jar' -File)
if ($jarFiles.Count -ne 1) {
    throw 'Не найден единственный JAR MSC Launcher Metro.'
}

$bundledJava = Join-Path $PSScriptRoot 'runtime\bin\javaw.exe'
$javaw = if (Test-Path -LiteralPath $bundledJava) {
    $bundledJava
} else {
    (Get-Command javaw.exe -ErrorAction Stop).Source
}

$root = 'HKCU:\Software\Classes\msc-launcher'
New-Item -Path $root -Force | Out-Null
Set-Item -Path $root -Value 'URL:MSC Launcher Metro'
New-ItemProperty -Path $root -Name 'URL Protocol' -Value '' -Force | Out-Null
New-Item -Path "$root\DefaultIcon" -Force | Out-Null
Set-Item -Path "$root\DefaultIcon" -Value "`"$javaw`",0"
New-Item -Path "$root\shell\open\command" -Force | Out-Null
$command = "`"$javaw`" -jar `"$($jarFiles[0].FullName)`" `"%1`""
Set-Item -Path "$root\shell\open\command" -Value $command

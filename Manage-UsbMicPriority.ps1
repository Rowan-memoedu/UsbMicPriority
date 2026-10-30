#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Start','Status','Stop','Recover','Inspect')][string]$Action,
    [string]$Serial,
    [string]$AdbPath = 'D:\Applications\Android\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$remoteDir = '/data/local/tmp/usb-mic-priority'
$remoteDex = "$remoteDir/usb-mic-priority.dex"
$javaClass = 'io.memoedu.usbmicpriority.Main'
$artifactPath = 'D:\Artifacts\UsbMicPriority\build\usb-mic-priority.dex'
if ($Serial -match ':') { & $AdbPath connect $Serial | Write-Host }
if (-not $Serial) {
    $available = @(& $AdbPath devices | Select-String -Pattern '^(\S+)\s+device$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($available.Count -ne 1) { throw 'Specify -Serial when zero or multiple devices are connected.' }
    $Serial = $available[0]
}
if ((& $AdbPath -s $Serial get-state).Trim() -ne 'device') { throw 'Device is not authorized/connected.' }
function Invoke-RouteCommand([string]$Command) {
    & $AdbPath -s $Serial shell "CLASSPATH=$remoteDex app_process /system/bin $javaClass $Command"
}
$installed = ((& $AdbPath -s $Serial shell "if [ -f $remoteDex ]; then echo present; else echo absent; fi") -join '').Trim() -eq 'present'
if ($Action -eq 'Start') {
    $buildInputs = @((Get-Item -LiteralPath (Join-Path $PSScriptRoot 'Build.ps1')))
    $buildInputs += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src'),(Join-Path $PSScriptRoot 'tests') -Filter '*.java' -Recurse)
    $newestInput = $buildInputs | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not (Test-Path -LiteralPath $artifactPath) -or $newestInput.LastWriteTimeUtc -gt (Get-Item -LiteralPath $artifactPath).LastWriteTimeUtc) {
        & (Join-Path $PSScriptRoot 'Build.ps1') | Write-Host
    }
    if ($installed) {
        $oldStatus = (Invoke-RouteCommand status) | ConvertFrom-Json
        if ($oldStatus.processAlive) { Invoke-RouteCommand stop | Write-Host }
        else { Invoke-RouteCommand recover | Write-Host }
    }
    & $AdbPath -s $Serial shell mkdir -p $remoteDir
    & $AdbPath -s $Serial shell chmod 700 $remoteDir
    & $AdbPath -s $Serial push $artifactPath $remoteDex | Write-Host
    & $AdbPath -s $Serial shell "CLASSPATH=$remoteDex nohup setsid app_process /system/bin $javaClass run >$remoteDir/launcher.log 2>&1 < /dev/null &"
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        Start-Sleep -Milliseconds 400
        $status = (Invoke-RouteCommand status) | ConvertFrom-Json
        if ($status.processAlive -and $status.state -in @('ACTIVE_USB','WAITING_USB')) {
            $status | ConvertTo-Json -Depth 4
            exit 0
        }
    }
    & $AdbPath -s $Serial shell cat "$remoteDir/launcher.log" | Write-Host
    throw ('Service failed to become ready: {0}' -f ($status | ConvertTo-Json -Compress))
}
if (-not $installed) { [pscustomobject]@{state='NOT_INSTALLED'; processAlive=$false} | ConvertTo-Json; exit 0 }
switch ($Action) {
    'Status' { Invoke-RouteCommand status }
    'Stop' { Invoke-RouteCommand stop }
    'Recover' { Invoke-RouteCommand recover }
    'Inspect' { Invoke-RouteCommand inspect }
}

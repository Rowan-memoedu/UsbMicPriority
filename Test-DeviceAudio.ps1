#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$AdbPath = 'D:\Applications\Android\platform-tools\adb.exe',
    [string]$JavaHome = 'D:\Applications\Java\temurin-21.0.12.1+1',
    [string]$AndroidSdk = 'D:\Applications\Android\sdk'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$outDir = 'D:\Artifacts\UsbMicPriority\device-tests'
$classesDir = Join-Path $outDir 'classes'
$dexDir = Join-Path $outDir 'dex'
New-Item -ItemType Directory -Path $classesDir,$dexDir -Force | Out-Null
$platform = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'platforms') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$tools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName 'android.jar'
$testSource = Join-Path $PSScriptRoot 'tests\device\CommunicationCanary.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -classpath $androidJar -d $classesDir $testSource
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $classes = @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -Recurse)
    & (Join-Path $tools.FullName 'd8.bat') --min-api 33 --lib $androidJar --output $dexDir $classes.FullName
} finally { $env:JAVA_HOME = $previousJavaHome }
if ($Serial -match ':') { & $AdbPath connect $Serial | Write-Host }
& $AdbPath -s $Serial push (Join-Path $dexDir 'classes.dex') /data/local/tmp/usb-mic-priority/communication-test.dex | Write-Host
& $AdbPath -s $Serial shell 'CLASSPATH=/data/local/tmp/usb-mic-priority/communication-test.dex app_process /system/bin io.memoedu.usbmicpriority.CommunicationCanary' |
    Tee-Object -FilePath (Join-Path $outDir 'communication-canary.txt')

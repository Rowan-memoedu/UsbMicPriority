#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$JavaHome = 'D:\Applications\Java\temurin-21.0.12.1+1',
    [string]$AndroidSdk = 'D:\Applications\Android\sdk',
    [string]$OutputRoot = 'D:\Artifacts\UsbMicPriority\build'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$javaBin = Join-Path $JavaHome 'bin'
$platform = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'platforms') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $platform -or -not $buildTools) { throw 'Android platform/build tools are missing.' }
$androidJar = Join-Path $platform.FullName 'android.jar'
$classesDir = Join-Path $OutputRoot 'classes'
$testDir = Join-Path $OutputRoot 'test-classes'
$dexDir = Join-Path $OutputRoot 'dex'
New-Item -ItemType Directory -Path $classesDir,$testDir,$dexDir -Force | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse)
& (Join-Path $javaBin 'javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -classpath $androidJar -d $classesDir $sourceFiles.FullName
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.java' -Recurse |
    Where-Object { $_.FullName -notmatch '[\\/]device[\\/]' })
& (Join-Path $javaBin 'javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -classpath $classesDir -d $testDir $testFiles.FullName
& (Join-Path $javaBin 'java.exe') -ea -classpath "$classesDir;$testDir" io.memoedu.usbmicpriority.RoutingControllerTest
$classFiles = @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -Recurse)
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & (Join-Path $buildTools.FullName 'd8.bat') --min-api 33 --lib $androidJar --output $dexDir $classFiles.FullName
} finally { $env:JAVA_HOME = $previousJavaHome }
$artifactPath = Join-Path $OutputRoot 'usb-mic-priority.dex'
Copy-Item -LiteralPath (Join-Path $dexDir 'classes.dex') -Destination $artifactPath -Force
Write-Output "ARTIFACT $artifactPath"

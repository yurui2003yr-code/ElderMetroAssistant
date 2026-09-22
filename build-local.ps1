param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolRoot = Join-Path (Split-Path $projectRoot -Parent) '.tools'
$bundledJdk = Join-Path $toolRoot 'jdk\jdk-17.0.18+8'
if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = $bundledJdk }
$bundledSdk = Join-Path $toolRoot 'android-sdk'
if (Test-Path -LiteralPath $bundledSdk) { $env:ANDROID_HOME = $bundledSdk }
if (Test-Path -LiteralPath $toolRoot) { $env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home' }
Push-Location $projectRoot
try {
    $tasks = @('test', 'lint', 'assembleDebug', '--console=plain')
    if ($DeviceTests) { $tasks += 'connectedDebugAndroidTest' }
    & '.\gradlew.bat' @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

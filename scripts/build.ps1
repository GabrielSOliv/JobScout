param([switch]$Check)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) {
    $studioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath "$studioJbr/bin/java.exe") { $env:JAVA_HOME = $studioJbr }
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home' }
$tasks = @(':app:assembleDebug')
if ($Check) { $tasks += @(':domain:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest') }
& "$projectRoot/gradlew.bat" @tasks --console=plain --no-watch-fs --max-workers=2 --no-parallel
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "APK: $projectRoot/app/build/outputs/apk/debug/app-debug.apk"

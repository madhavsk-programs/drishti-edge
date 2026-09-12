# Keep raw arguments: an advanced PowerShell parameter block consumes adb's
# -d as PowerShell's common -Debug parameter instead of forwarding it.
$CommandArgs = @($args)

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolchainRoot = Join-Path $projectRoot '.android-toolchain'
$sdkRoot = Join-Path $toolchainRoot 'sdk'
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'

if (-not (Test-Path (Join-Path $sdkRoot 'platform-tools\adb.exe'))) {
    throw 'Android SDK is missing. Run the environment setup again.'
}

if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    throw 'Java 21 is missing. Install Temurin 21, then retry.'
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolchainRoot 'user-home'
$env:GRADLE_USER_HOME = Join-Path $toolchainRoot 'gradle-user-home'
$env:PATH = "$(Join-Path $javaHome 'bin');$(Join-Path $sdkRoot 'platform-tools');$env:PATH"

if ($CommandArgs.Count -gt 0 -and $CommandArgs[0] -eq 'adb') {
    & (Join-Path $sdkRoot 'platform-tools\adb.exe') @($CommandArgs | Select-Object -Skip 1)
    exit $LASTEXITCODE
}

Push-Location (Join-Path $projectRoot 'apps\android')
try {
    & .\gradlew.bat @CommandArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}

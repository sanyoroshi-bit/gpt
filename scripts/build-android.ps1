param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleCommand = ''
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot)) { throw 'Set ANDROID_HOME or pass -SdkRoot for Android SDK 36 and Build Tools 35.0.0.' }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set JAVA_HOME or pass -JavaHome for JDK 17 or 21.' }
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkRoot).Path
$taskCacheRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'ten-android-cache'
$env:ANDROID_USER_HOME = Join-Path $taskCacheRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $taskCacheRoot 'gradle-home'
$taskKeyDir = Join-Path $taskRepo '.ten-cache\keys'
New-Item -ItemType Directory -Force -Path $taskKeyDir | Out-Null
$env:TEN_DEBUG_KEYSTORE = Join-Path $taskKeyDir 'ten-debug.keystore'
if (-not (Test-Path -LiteralPath $env:TEN_DEBUG_KEYSTORE)) {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -noprompt -keystore $env:TEN_DEBUG_KEYSTORE -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=ten Local Debug,O=ten,C=JP'
    if ($LASTEXITCODE -ne 0) { throw 'Debug key creation failed.' }
}
# AGP on Windows requires an ASCII project path. Copy source only; never move or delete the workspace.
$taskStage = Join-Path ([System.IO.Path]::GetTempPath()) ('ten-android-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
$taskSource = Join-Path $taskRepo 'android'
foreach ($taskFile in Get-ChildItem -LiteralPath $taskSource -Recurse -File) {
    $taskRelative = $taskFile.FullName.Substring($taskSource.Length + 1)
    if ($taskRelative -match '(^|[\\/])(build|\.gradle)([\\/]|$)|(^|[\\/])local\.properties$|\.(jks|keystore)$') { continue }
    $taskDestination = Join-Path $taskStage $taskRelative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskDestination) | Out-Null
    Copy-Item -LiteralPath $taskFile.FullName -Destination $taskDestination
}
$taskSdkProperty = $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $taskStage 'local.properties'), "sdk.dir=$taskSdkProperty`n", [System.Text.UTF8Encoding]::new($false))
if (-not $GradleCommand) { $GradleCommand = Join-Path $taskStage 'gradlew.bat' }
Write-Output "Build source: $taskStage"
& $GradleCommand -p $taskStage testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw "Android verification failed. Inspect $taskStage" }
$taskBuiltApk = Join-Path $taskStage 'app\build\outputs\apk\debug\app-debug.apk'
& (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') verify --verbose --print-certs $taskBuiltApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$taskDownloads = Join-Path $taskRepo 'public\downloads'
New-Item -ItemType Directory -Force -Path $taskDownloads | Out-Null
$taskApk = Join-Path $taskDownloads 'ten-launcher-debug.apk'
Copy-Item -LiteralPath $taskBuiltApk -Destination $taskApk
$taskReports = Join-Path $taskRepo '.ten-cache\android-reports'
New-Item -ItemType Directory -Force -Path $taskReports | Out-Null
Copy-Item -LiteralPath (Join-Path $taskStage 'app\build\reports') -Destination $taskReports -Recurse -Force
$taskHash = Get-FileHash -LiteralPath $taskApk -Algorithm SHA256
Set-Content -LiteralPath "$taskApk.sha256" -Value ($taskHash.Hash.ToLowerInvariant() + '  ten-launcher-debug.apk') -Encoding ascii
$taskHash
Write-Output "Verified debug APK: $taskApk"

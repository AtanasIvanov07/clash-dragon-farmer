param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolPaths = Get-Content -LiteralPath (Join-Path $projectRoot '.toolchain\paths.json') | ConvertFrom-Json
$env:JAVA_HOME = $toolPaths.jdk
$env:ANDROID_HOME = $toolPaths.sdk
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.toolchain\gradle-user'
$nativeRoot = Join-Path $projectRoot 'native'
$sdkProperty = $toolPaths.sdk.Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $nativeRoot 'local.properties') -Value "sdk.dir=$sdkProperty" -Encoding utf8 -NoNewline
& (Join-Path $toolPaths.gradle 'bin\gradle.bat') -p $nativeRoot --console=plain @Tasks
if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }

# Shared by the other scripts: finds Java 17 and the Android SDK on this PC.
$ErrorActionPreference = 'Stop'

function Find-Dir([string[]]$candidates, [string]$required) {
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c $required))) { return (Resolve-Path -LiteralPath $c).Path }
    }
    return $null
}

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:JavaHome = Find-Dir @($env:JAVA_HOME, (Join-Path $env:USERPROFILE '.jdks\jdk-17.0.21.0.2+1'), (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')) 'bin\java.exe'
# sdk.dir from local.properties, as Android Studio writes it (colons and backslashes may be escaped).
$props = Join-Path $ProjectRoot 'local.properties'
$localSdk = $null
if (Test-Path -LiteralPath $props) {
    $line = Get-Content -LiteralPath $props | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
    if ($line) { $localSdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
$script:SdkRoot = Find-Dir @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $localSdk, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) 'platform-tools\adb.exe'
if (-not $JavaHome) { throw 'Java 17 not found (set JAVA_HOME).' }
if (-not $SdkRoot) { throw 'Android SDK not found (set ANDROID_HOME).' }

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = Join-Path $ProjectRoot '.tools\avd'
$env:Path = (Join-Path $JavaHome 'bin') + ';' + (Join-Path $SdkRoot 'platform-tools') + ';' + $env:Path
$script:Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$ndk = Get-ChildItem (Join-Path $SdkRoot 'ndk') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if ($ndk) { $env:ANDROID_NDK_HOME = $ndk.FullName; $script:NdkHome = $ndk.FullName }

# adb without PowerShell 5.1 turning its stderr chatter ("daemon not running") into errors.
function Invoke-Adb {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $script:Adb @args 2>$null } finally { $ErrorActionPreference = $old }
}

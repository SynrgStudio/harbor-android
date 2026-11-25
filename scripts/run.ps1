# Builds, installs and opens Harbor on a device.
#   .\scripts\run.ps1                         # debug build on the emulator (emulator-5590)
#   .\scripts\run.ps1 -Release -Serial <ip:port>   # fast build on the phone (wireless debugging)
param([string]$Serial = 'emulator-5590', [switch]$Release, [switch]$NoBuild)
. "$PSScriptRoot\env.ps1"
Set-Location $ProjectRoot

$variant = if ($Release) { 'release' } else { 'debug' }
$task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
if (-not $NoBuild) {
    & .\gradlew.bat $task --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'Falló el build.' }
}

$abi = (Invoke-Adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
$apk = Join-Path $ProjectRoot "app\build\outputs\apk\$variant\app-$abi-$variant.apk"
if (-not (Test-Path $apk)) { throw "No hay APK para $abi en $apk" }
$result = (Invoke-Adb -s $Serial install -r $apk) -join ' '
if ($result -match 'UPDATE_INCOMPATIBLE') {
    # Debug builds use the debug key, releases the release key: Android will not swap one for the other.
    throw "La app instalada tiene otra firma (debug vs release). Desinstalala (borra sus datos locales): adb -s $Serial uninstall com.synrg.harbor"
}
if ($LASTEXITCODE -ne 0) { throw "Falló la instalación: $result" }
Invoke-Adb -s $Serial shell am start -n com.synrg.harbor/.MainActivity | Out-Null
if ($Release) {
    # Sideloaded apps only get their Baseline Profile compiled when the phone is idle and charging;
    # do it now so the build is measured (and used) as it will run.
    Start-Sleep -Seconds 3
    Invoke-Adb -s $Serial shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE com.synrg.harbor/androidx.profileinstaller.ProfileInstallReceiver | Out-Null
    Invoke-Adb -s $Serial shell am force-stop com.synrg.harbor | Out-Null
    Invoke-Adb -s $Serial shell cmd package compile -f -m speed-profile com.synrg.harbor | Out-Null
    Invoke-Adb -s $Serial shell am start -n com.synrg.harbor/.MainActivity | Out-Null
}
"Instalado $variant ($abi) en $Serial"

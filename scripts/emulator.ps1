# Starts the project's test emulator (creates it the first time) and waits for boot.
#   .\scripts\emulator.ps1            # headless, for screenshots
#   .\scripts\emulator.ps1 -Window    # with a window you can use
# -Gpu host uses the PC's graphics card; swiftshader_indirect (software) crashed qemu under the
# baseline-profile run.
param([switch]$Window, [int]$Port = 5590, [string]$Gpu = 'host')
. "$PSScriptRoot\env.ps1"

$avd = 'HarborTest35'
$serial = "emulator-$Port"
$image = 'system-images;android-35;default;x86_64'
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null

if (-not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$avd.ini"))) {
    $avdManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
    'no' | & $avdManager create avd -n $avd -k $image -d pixel_7 -p (Join-Path $env:ANDROID_AVD_HOME "$avd.avd") | Out-Null
    $cfg = Join-Path $env:ANDROID_AVD_HOME "$avd.avd\config.ini"
    Add-Content $cfg "`nhw.keyboard=yes`nhw.ramSize=3072`nhw.cpu.ncore=4`ndisk.dataPartition.size=8G`n"
}

$state = Invoke-Adb -s $serial get-state 2>$null
if ($state -ne 'device') {
    $emulator = Join-Path $SdkRoot 'emulator\emulator.exe'
    $emuArgs = @('-avd', $avd, '-port', "$Port", '-no-audio', '-no-snapshot-save', '-no-boot-anim', '-camera-back', 'none', '-camera-front', 'none', '-no-metrics')
    $emuArgs += @('-gpu', $Gpu)
    if (-not $Window) { $emuArgs += '-no-window' }
    $logDir = Join-Path $ProjectRoot '.tools'
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir 'emulator.out.txt') -RedirectStandardError (Join-Path $logDir 'emulator.err.txt') | Out-Null
}

$deadline = (Get-Date).AddSeconds(180)
do {
    Start-Sleep -Seconds 2
    $boot = (Invoke-Adb -s $serial shell getprop sys.boot_completed 2>$null)
} while ($boot -ne '1' -and (Get-Date) -lt $deadline)
if ($boot -ne '1') { throw "The emulator did not boot. See .tools\emulator.err.txt" }
Invoke-Adb -s $serial shell settings put global window_animation_scale 1 | Out-Null
"Listo: $serial"

# Regenerates the Baseline Profile on the project emulator (never on a phone that is also
# connected) and leaves it in app/src/release/generated/baselineProfiles. Commit the result.
. "$PSScriptRoot\env.ps1"
& "$PSScriptRoot\emulator.ps1" | Out-Host
$env:ANDROID_SERIAL = 'emulator-5590'
Set-Location $ProjectRoot
$ErrorActionPreference = 'Continue'
& .\gradlew.bat :app:generateReleaseBaselineProfile --console=plain 2>&1 | ForEach-Object { "$_" } |
    Where-Object { $_ -match 'BUILD|FAIL|error|Exception|Wrote|baseline' }
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw 'No se pudo generar el perfil.' }
Get-ChildItem (Join-Path $ProjectRoot 'app\src\release\generated\baselineProfiles') | Select-Object Name, Length

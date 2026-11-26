# Builds the signed release APKs and publishes them as a GitHub release (tag v<versionName>).
#   .\scripts\release.ps1 -NotesFile notas.md     # or -Notes "Qué cambió"; -Draft to review first
# Needs: the release key in local.properties (harbor.signing.*), the native libs built, gh logged in,
# and a clean working tree (the tag must point at what was built).
param([string]$Notes, [string]$NotesFile, [switch]$Draft)
. "$PSScriptRoot\env.ps1"
Set-Location $ProjectRoot

$version = [regex]::Match((Get-Content app\build.gradle.kts -Raw), 'versionName = [^
]*"(\d[^"]*)"').Groups[1].Value
if (-not $version) { throw 'No encontré versionName en app/build.gradle.kts.' }
$tag = "v$version"
if (-not (Select-String -Path local.properties -Pattern '^harbor\.signing\.storeFile=' -Quiet)) {
    throw 'Falta la clave de release en local.properties (harbor.signing.*).'
}

$ErrorActionPreference = 'Continue'
if (git status --porcelain) { throw 'Hay cambios sin commitear: commiteá antes de publicar.' }
gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) { throw "Ya existe el release ${tag}: subí versionName primero." }

& .\gradlew.bat :app:assembleRelease --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Falló el build.' }

$buildTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$out = Join-Path $ProjectRoot 'artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
$sha = $null
# Stable names: Obtainium and other installers pick the file by its ABI.
$files = foreach ($abi in 'arm64-v8a', 'x86_64') {
    $dst = Join-Path $out "Harbor-$version-$abi.apk"
    Copy-Item "app\build\outputs\apk\release\app-$abi-release.apk" $dst -Force
    $certs = (& $apksigner verify --print-certs $dst 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "La firma de $dst no verifica." }
    if ($certs -match 'CN=Android Debug') { throw "$dst quedó firmado con la clave de debug." }
    $sha = [regex]::Match($certs, 'SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
    $dst
}

$body = if ($NotesFile) { Get-Content $NotesFile -Raw -Encoding UTF8 } elseif ($Notes) { $Notes } else { "Harbor $version" }
$body += "`n`n---`n**arm64-v8a**: celulares. **x86_64**: emuladores y Chromebooks.`n`nCertificado de firma (SHA-256): ``$sha``"
$notesPath = Join-Path $env:TEMP "harbor-release-$version.md"
[IO.File]::WriteAllText($notesPath, $body, (New-Object Text.UTF8Encoding $false))

git tag -a $tag -m "Harbor $version"
git push origin $tag 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "No se pudo subir el tag $tag." }
$ghArgs = @('release', 'create', $tag) + $files + @('--title', "Harbor $version", '--notes-file', $notesPath)
if ($Draft) { $ghArgs += '--draft' }
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el release.' }
Remove-Item $notesPath

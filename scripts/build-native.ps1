# Cross-compiles the torrent engine (native/, Rust + librqbit) into app/src/main/jniLibs.
# Run it after changing native/ or on a fresh checkout (the .so files are not in git).
#   .\scripts\build-native.ps1               # arm64 (phone) + x86_64 (emulator)
#   .\scripts\build-native.ps1 -Abi arm64-v8a
param([string[]]$Abi = @('arm64-v8a', 'x86_64'), [int]$Api = 29)
. "$PSScriptRoot\env.ps1"
if (-not $NdkHome) { throw 'The NDK is missing: sdkmanager --install "ndk;29.0.14206865"' }
$cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
$bin = Join-Path $NdkHome 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$targets = @{ 'arm64-v8a' = 'aarch64-linux-android'; 'x86_64' = 'x86_64-linux-android' }

foreach ($a in $Abi) {
    $t = $targets[$a]
    if (-not $t) { throw "Unsupported ABI: $a" }
    $clang = Join-Path $bin "$t$Api-clang.cmd"
    $envName = $t.ToUpper().Replace('-', '_')
    Set-Item "env:CARGO_TARGET_${envName}_LINKER" $clang
    Set-Item "env:CC_$($t.Replace('-', '_'))" $clang
    Set-Item "env:AR_$($t.Replace('-', '_'))" (Join-Path $bin 'llvm-ar.exe')
    # cargo reports progress on stderr; PowerShell 5.1 would treat that as a failure under 'Stop'.
    $ErrorActionPreference = 'Continue'
    & $cargo build --release --target $t --manifest-path (Join-Path $ProjectRoot 'native\Cargo.toml') 2>&1 |
        ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^\s+(Compiling|Downloaded|Downloading) ' }
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Build failed for $a" }
    $out = Join-Path $ProjectRoot "app\src\main\jniLibs\$a"
    New-Item -ItemType Directory -Force $out | Out-Null
    Copy-Item (Join-Path $ProjectRoot "native\target\$t\release\libharbor_torrent.so") $out -Force
    $size = [math]::Round((Get-Item (Join-Path $out 'libharbor_torrent.so')).Length / 1MB, 1)
    "$a listo ($size MB)"
}

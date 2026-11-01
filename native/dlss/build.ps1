# Build script for vitrail_dlss native bridge DLL
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $scriptDir "build"

Write-Host "Configuring CMake for vitrail_dlss in $buildDir..."
cmake -B $buildDir -S $scriptDir
cmake --build $buildDir --config Release

$dllPath = Join-Path $buildDir "Release\vitrail_dlss.dll"
if (Test-Path $dllPath) {
    Write-Host "Successfully built vitrail_dlss.dll at $dllPath" -ForegroundColor Green

    $resourceDir = [System.IO.Path]::GetFullPath((Join-Path $scriptDir "..\..\common\src\main\resources\natives\windows\x86_64"))
    if (-not (Test-Path $resourceDir)) {
        New-Item -ItemType Directory -Path $resourceDir -Force | Out-Null
    }
    Copy-Item -Path $dllPath -Destination (Join-Path $resourceDir "vitrail_dlss.dll") -Force
    Write-Host "Copied vitrail_dlss.dll to $resourceDir" -ForegroundColor Cyan
} else {
    Write-Error "Failed to build vitrail_dlss.dll"
}

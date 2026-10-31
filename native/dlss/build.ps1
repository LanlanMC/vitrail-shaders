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
} else {
    Write-Error "Failed to build vitrail_dlss.dll"
}

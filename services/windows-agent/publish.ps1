#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Publishes the Maimai Home Agent as a self-contained, single-file Windows x64 executable.

.DESCRIPTION
    Three-step pipeline:
      1. pnpm install (PC Web dependencies, frozen lockfile)
      2. pnpm build   (PC Web -> services/windows-agent/src/MaimaiHomeAgent/wwwroot/)
      3. dotnet publish (single-file, self-contained, embedded debug, no trimming)

    Each step exits the script on failure. Final output:
      services/windows-agent/src/MaimaiHomeAgent/bin/publish/win-x64/MaimaiHomeAgent.exe

.NOTES
    - dotnet path: detected via PATH, fall back to scoop default if missing.
    - Trimming is intentionally OFF: AudioSwitcher / NAudio / H.NotifyIcon are not
      verified trim-compatible. Re-evaluate after Wave 5.
#>

[CmdletBinding()]
param(
    [string]$DotnetPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Resolve repository paths relative to this script (services/windows-agent/publish.ps1)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Resolve-Path (Join-Path $scriptDir '..\..') | Select-Object -ExpandProperty Path
$agentDir  = $scriptDir
$csproj    = Join-Path $agentDir 'src\MaimaiHomeAgent\MaimaiHomeAgent.csproj'
$publishDir = Join-Path $agentDir 'src\MaimaiHomeAgent\bin\publish\win-x64'
$exePath   = Join-Path $publishDir 'MaimaiHomeAgent.exe'

function Resolve-Dotnet {
    param([string]$Override)
    if ($Override) {
        if (-not (Test-Path -LiteralPath $Override)) {
            throw "dotnet not found at -DotnetPath: $Override"
        }
        return $Override
    }
    $cmd = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Path }
    $fallback = 'C:\Users\abbey\scoop\apps\dotnet9-sdk\9.0.314\dotnet.exe'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw "dotnet SDK not found. Add to PATH or pass -DotnetPath <path-to-dotnet.exe>."
}

function Invoke-Step {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [scriptblock]$Action
    )
    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "Step failed: $Name (exit $LASTEXITCODE)"
    }
}

try {
    $dotnet = Resolve-Dotnet -Override $DotnetPath
    Write-Host "Repo root : $repoRoot"
    Write-Host "Agent dir : $agentDir"
    Write-Host "dotnet    : $dotnet"

    # Step 1: pnpm install (frozen lockfile)
    Invoke-Step -Name 'pnpm install (apps/pc-web, frozen lockfile)' -Action {
        & pnpm --dir (Join-Path $repoRoot 'apps\pc-web') install --frozen-lockfile
    }

    # Step 2: pnpm build (vite -> wwwroot)
    Invoke-Step -Name 'pnpm build (apps/pc-web -> wwwroot)' -Action {
        & pnpm --dir (Join-Path $repoRoot 'apps\pc-web') build
    }

    # Sanity-check: PC Web bundle must be on disk before dotnet publish
    $indexHtml = Join-Path $agentDir 'src\MaimaiHomeAgent\wwwroot\index.html'
    if (-not (Test-Path -LiteralPath $indexHtml)) {
        throw "PC Web bundle missing at $indexHtml after build step."
    }

    # Step 3: dotnet publish (single-file, self-contained)
    Invoke-Step -Name 'dotnet publish (Release, win-x64, self-contained, single-file)' -Action {
        & $dotnet publish $csproj `
            -c Release `
            -r win-x64 `
            --self-contained `
            -p:PublishProfile=win-x64 `
            -p:PublishSingleFile=true `
            -p:IncludeNativeLibrariesForSelfExtract=true `
            -p:EnableCompressionInSingleFile=true `
            -p:DebugType=embedded `
            -p:PublishTrimmed=false `
            -nologo
    }

    if (-not (Test-Path -LiteralPath $exePath)) {
        throw "Publish completed but exe not found at $exePath"
    }

    $exeInfo = Get-Item -LiteralPath $exePath
    $sizeMB = [math]::Round($exeInfo.Length / 1MB, 2)

    Write-Host ""
    Write-Host "==> Publish succeeded" -ForegroundColor Green
    Write-Host "Exe path : $exePath"
    Write-Host "Exe size : $sizeMB MB ($($exeInfo.Length) bytes)"

    if ($exeInfo.Length -gt (100 * 1MB)) {
        Write-Warning "Exe size exceeds 100 MB target. Investigate dependencies before shipping."
    }

    exit 0
}
catch {
    Write-Host ""
    Write-Error $_
    exit 1
}

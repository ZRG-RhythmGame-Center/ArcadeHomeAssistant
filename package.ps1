#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds and packages project deliverables into outputs/<timestamp>/.

.DESCRIPTION
    Produces:
      - Windows Agent win-x64 zip
      - Android arm64 release APK
      - manifest.json with size and SHA256 hashes

    The timestamp folder avoids overwriting earlier packages.
#>

[CmdletBinding()]
param(
    [string]$Timestamp,
    [string]$DotnetPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = $PSScriptRoot
if (-not $repoRoot) {
    $repoRoot = (Resolve-Path .).Path
}

function Assert-InWorkspace {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Workspace
    )
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolved.StartsWith($Workspace, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside workspace: $resolved"
    }
    return $resolved
}

function Remove-DirectoryInsideWorkspace {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Workspace
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolved = Assert-InWorkspace -Path $Path -Workspace $Workspace
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function New-TimestampedOutputDirectory {
    param(
        [Parameter(Mandatory)] [string]$OutputRoot,
        [Parameter(Mandatory)] [string]$BaseName
    )
    New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
    $candidate = Join-Path $OutputRoot $BaseName
    if (-not (Test-Path -LiteralPath $candidate)) {
        New-Item -ItemType Directory -Path $candidate | Out-Null
        return $candidate
    }

    for ($i = 1; $i -le 99; $i++) {
        $suffix = '{0:d2}' -f $i
        $candidate = Join-Path $OutputRoot "$BaseName-$suffix"
        if (-not (Test-Path -LiteralPath $candidate)) {
            New-Item -ItemType Directory -Path $candidate | Out-Null
            return $candidate
        }
    }

    throw "Unable to create a unique output directory for timestamp: $BaseName"
}

function Invoke-External {
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

$workspace = (Resolve-Path -LiteralPath $repoRoot).Path
$stamp = if ($Timestamp) { $Timestamp } else { Get-Date -Format 'yyyyMMdd-HHmmss' }
$outputRoot = Join-Path $workspace 'outputs'
$packageDir = New-TimestampedOutputDirectory -OutputRoot $outputRoot -BaseName $stamp

$publishDir = Join-Path $workspace 'services\windows-agent\src\MaimaiHomeAgent\bin\publish\win-x64'
$publishScript = Join-Path $workspace 'services\windows-agent\publish.ps1'
$agentExe = Join-Path $publishDir 'MaimaiHomeAgent.exe'
$androidDir = Join-Path $workspace 'apps\mobile-android'
$androidApk = Join-Path $androidDir 'app\build\outputs\apk\release\app-arm64-v8a-release.apk'

Write-Host "Repo root : $workspace"
Write-Host "Package   : $packageDir"

Remove-DirectoryInsideWorkspace -Path $publishDir -Workspace $workspace

$pwshCommand = Get-Command pwsh -ErrorAction SilentlyContinue
if (-not $pwshCommand) {
    $pwshCommand = Get-Command powershell -ErrorAction Stop
}

$publishArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $publishScript)
if ($DotnetPath) {
    $publishArgs += @('-DotnetPath', $DotnetPath)
}

Invoke-External -Name 'publish Windows Agent' -Action {
    & $pwshCommand.Path @publishArgs
}

Invoke-External -Name 'assemble Android release APK' -Action {
    Push-Location $androidDir
    try {
        & .\gradlew.bat assembleRelease
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $agentExe)) {
    throw "Missing Windows Agent executable: $agentExe"
}
if (-not (Test-Path -LiteralPath $androidApk)) {
    throw "Missing Android APK: $androidApk"
}

$agentZip = Join-Path $packageDir "maimai-home-agent-win-x64-$stamp.zip"
$apkDest = Join-Path $packageDir "maimai-home-android-arm64-release-$stamp.apk"

Compress-Archive -Path (Join-Path $publishDir '*') -DestinationPath $agentZip -Force
Copy-Item -LiteralPath $androidApk -Destination $apkDest -Force

$artifacts = @($agentZip, $apkDest) | ForEach-Object {
    $item = Get-Item -LiteralPath $_
    $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
    [pscustomobject]@{
        name = $item.Name
        path = $item.FullName
        bytes = $item.Length
        sizeMB = [math]::Round($item.Length / 1MB, 2)
        sha256 = $hash.Hash
    }
}

$manifest = [pscustomobject]@{
    createdAt = (Get-Date).ToString('o')
    packageDir = (Resolve-Path -LiteralPath $packageDir).Path
    artifacts = $artifacts
}

$manifestPath = Join-Path $packageDir 'manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host ""
Write-Host "==> Package succeeded" -ForegroundColor Green
$artifacts | Format-Table -AutoSize
Write-Host "Manifest: $manifestPath"

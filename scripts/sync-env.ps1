[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [string]$InfisicalExecutable
)

$ErrorActionPreference = "Stop"
if (-not $ProjectRoot) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}

$configurationPath = Join-Path $ProjectRoot ".infisical.json"
$targetPath = Join-Path $ProjectRoot ".memoryos-dev.yaml"
$temporaryPath = Join-Path $ProjectRoot (".memoryos-dev.yaml.{0}.tmp" -f [guid]::NewGuid().ToString("N"))

if (-not (Test-Path $configurationPath -PathType Leaf)) {
    throw "Missing Infisical configuration: $configurationPath"
}

$configuration = Get-Content -Path $configurationPath -Raw | ConvertFrom-Json
if (-not $configuration.workspaceId) { throw "Infisical workspaceId is required" }
if (-not $configuration.defaultEnvironment) { throw "Infisical defaultEnvironment is required" }
if (-not $configuration.domain) { throw "Infisical domain is required" }

if (-not $InfisicalExecutable) {
    $command = Get-Command infisical -ErrorAction SilentlyContinue
    if ($command) {
        $InfisicalExecutable = $command.Source
    }
    elseif ($env:OS -eq "Windows_NT") {
        $winGetPackages = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
        $InfisicalExecutable = Get-ChildItem `
            -Path $winGetPackages `
            -Directory `
            -Filter "infisical.infisical_*" `
            -ErrorAction SilentlyContinue |
            Get-ChildItem -Filter "infisical.exe" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }

    if (-not $InfisicalExecutable) {
        throw "Infisical CLI is not available. Install it with: winget install infisical"
    }
}

$env:INFISICAL_DOMAIN = $configuration.domain

try {
    $global:LASTEXITCODE = 0
    & $InfisicalExecutable export `
        --projectId=$($configuration.workspaceId) `
        --env=$($configuration.defaultEnvironment) `
        --path=$($configuration.defaultSecretPath) `
        --format=yaml `
        --output-file=$temporaryPath `
        --silent

    if ($LASTEXITCODE -ne 0) {
        throw "Infisical export failed with exit code $LASTEXITCODE"
    }
    if (-not (Test-Path $temporaryPath -PathType Leaf)) {
        throw "Infisical did not produce a Spring configuration cache"
    }

    $yaml = [System.IO.File]::ReadAllText($temporaryPath, [System.Text.Encoding]::UTF8)
    $variables = @($yaml -split "\r\n|\n|\r" | Where-Object {
        $line = $_.Trim()
        $line -and -not $line.StartsWith("#") -and
            $line -ne "{}" -and $line -ne "---" -and $line -ne "..."
    })
    if ($variables.Count -eq 0) {
        throw "Infisical returned an empty environment"
    }

    if ([System.IO.File]::Exists($targetPath)) {
        [System.IO.File]::Replace($temporaryPath, $targetPath, [NullString]::Value)
    }
    else {
        try {
            [System.IO.File]::Move($temporaryPath, $targetPath)
        }
        catch [System.IO.IOException] {
            # Another first launch may have created the cache after the existence check.
            if (-not [System.IO.File]::Exists($temporaryPath) -or
                -not [System.IO.File]::Exists($targetPath)) {
                throw
            }
            [System.IO.File]::Replace($temporaryPath, $targetPath, [NullString]::Value)
        }
    }

    Write-Host "Synchronized $($variables.Count) shared $($configuration.defaultEnvironment) variables from Infisical."
}
finally {
    Remove-Item -Path $temporaryPath -Force -ErrorAction SilentlyContinue
}

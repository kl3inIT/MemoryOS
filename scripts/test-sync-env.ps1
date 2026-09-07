$ErrorActionPreference = "Stop"

$scriptUnderTest = Join-Path $PSScriptRoot "sync-env.ps1"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("memoryos-sync-env-" + [guid]::NewGuid())
$cachePath = Join-Path $temporaryRoot ".memoryos-dev.yaml"
$fakeDriver = Join-Path $temporaryRoot "fake-infisical-driver.ps1"
$fakeCli = Join-Path $temporaryRoot "infisical.cmd"
$jobs = @()
$releasePath = $null
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)

function Test-ByteArraysEqual {
    param(
        [byte[]]$Left,
        [byte[]]$Right
    )

    if ($Left.Length -ne $Right.Length) {
        return $false
    }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) {
            return $false
        }
    }
    return $true
}

function Assert-FileBytes {
    param(
        [byte[]]$Expected,
        [string]$Path,
        [string]$Message
    )

    $actual = [System.IO.File]::ReadAllBytes($Path)
    if (-not (Test-ByteArraysEqual -Left $Expected -Right $actual)) {
        throw $Message
    }
}

function Assert-NoTemporaryCaches {
    param([string]$Root)

    $temporaryCaches = @(Get-ChildItem `
        -Path $Root `
        -Filter ".memoryos-dev.yaml*.tmp" `
        -File `
        -ErrorAction SilentlyContinue)
    if ($temporaryCaches.Count -ne 0) {
        throw "A temporary environment cache containing secrets was retained"
    }
}

function Invoke-SyncExpectingFailure {
    param(
        [string]$Root,
        [string]$Cli
    )

    $failure = $null
    try {
        & $scriptUnderTest -ProjectRoot $Root -InfisicalExecutable $Cli
    }
    catch {
        $failure = $_
    }

    if ($null -eq $failure) {
        throw "Expected environment synchronization to fail"
    }
}

$environmentVariableNames = @(
    "FAKE_INFISICAL_MODE",
    "FAKE_INFISICAL_SOURCE",
    "FAKE_INFISICAL_READY_DIRECTORY",
    "FAKE_INFISICAL_RELEASE_PATH",
    "INFISICAL_DOMAIN"
)
$originalEnvironment = @{}
foreach ($name in $environmentVariableNames) {
    $originalEnvironment[$name] = [System.Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

    $configuration = @'
{
  "workspaceId": "test-project",
  "defaultEnvironment": "dev",
  "gitBranchToEnvironmentMapping": {},
  "defaultSecretPath": "/",
  "domain": "https://secret.example.test"
}
'@
    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryRoot ".infisical.json"),
        $configuration,
        $utf8WithoutBom
    )

    $driver = @'
$ErrorActionPreference = "Stop"

$outputArgument = $args |
    Where-Object { $_.StartsWith("--output-file=") } |
    Select-Object -First 1
if (-not $outputArgument) {
    [Console]::Error.WriteLine("Missing output-file argument")
    exit 91
}
if ($args -notcontains "--format=yaml") {
    [Console]::Error.WriteLine("Expected YAML export format")
    exit 92
}

$outputPath = $outputArgument.Substring("--output-file=".Length)

if ($env:FAKE_INFISICAL_MODE -eq "fail") {
    if ($env:FAKE_INFISICAL_SOURCE) {
        [System.IO.File]::Copy($env:FAKE_INFISICAL_SOURCE, $outputPath, $true)
    }
    exit 23
}
if ($env:FAKE_INFISICAL_MODE -eq "empty") {
    [System.IO.File]::WriteAllText($outputPath, "{}" + [Environment]::NewLine)
    exit 0
}
if (-not $env:FAKE_INFISICAL_SOURCE) {
    [Console]::Error.WriteLine("Missing fake export source")
    exit 93
}

[System.IO.File]::Copy($env:FAKE_INFISICAL_SOURCE, $outputPath, $true)
if ($env:FAKE_INFISICAL_READY_DIRECTORY) {
    $readyPath = Join-Path `
        $env:FAKE_INFISICAL_READY_DIRECTORY `
        (([guid]::NewGuid().ToString("N")) + ".ready")
    [System.IO.File]::WriteAllText($readyPath, "")
    while (-not [System.IO.File]::Exists($env:FAKE_INFISICAL_RELEASE_PATH)) {
        Start-Sleep -Milliseconds 10
    }
}
exit 0
'@
    [System.IO.File]::WriteAllText($fakeDriver, $driver, $utf8WithoutBom)

    $wrapper = @'
@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0fake-infisical-driver.ps1" %*
exit /b %ERRORLEVEL%
'@
    [System.IO.File]::WriteAllText($fakeCli, $wrapper, $utf8WithoutBom)

    $initialYaml = 'ORIGINAL: "value"' + "`n"
    [System.IO.File]::WriteAllText($cachePath, $initialYaml, $utf8WithoutBom)

    $exportSource = Join-Path $temporaryRoot "export.yaml"
    $exportedYaml = 'QUOTED_VALUE: ''colon: # hash and "double quotes"''' + "`n" +
        ('UNICODE_VALUE: "Gr{0}{1}e"' -f [char]0x00FC, [char]0x00DF) + "`n"
    [System.IO.File]::WriteAllText($exportSource, $exportedYaml, $utf8WithoutBom)
    $expectedBytes = [System.IO.File]::ReadAllBytes($exportSource)

    $env:FAKE_INFISICAL_MODE = "success"
    $env:FAKE_INFISICAL_SOURCE = $exportSource
    Remove-Item Env:FAKE_INFISICAL_READY_DIRECTORY -ErrorAction SilentlyContinue
    Remove-Item Env:FAKE_INFISICAL_RELEASE_PATH -ErrorAction SilentlyContinue
    & $scriptUnderTest -ProjectRoot $temporaryRoot -InfisicalExecutable $fakeCli

    Assert-FileBytes `
        -Expected $expectedBytes `
        -Path $cachePath `
        -Message "Synchronization changed the exported YAML bytes"
    Assert-NoTemporaryCaches -Root $temporaryRoot

    $preservedBytes = [System.IO.File]::ReadAllBytes($cachePath)
    $failedExport = Join-Path $temporaryRoot "failed-export.yaml"
    [System.IO.File]::WriteAllText(
        $failedExport,
        ('REPLACEMENT: "must not be installed"' + "`n"),
        $utf8WithoutBom
    )
    $env:FAKE_INFISICAL_MODE = "fail"
    $env:FAKE_INFISICAL_SOURCE = $failedExport
    Invoke-SyncExpectingFailure `
        -Root $temporaryRoot `
        -Cli $fakeCli
    Assert-FileBytes `
        -Expected $preservedBytes `
        -Path $cachePath `
        -Message "A failed native export replaced the last valid environment cache"
    Assert-NoTemporaryCaches -Root $temporaryRoot

    $env:FAKE_INFISICAL_MODE = "empty"
    Invoke-SyncExpectingFailure `
        -Root $temporaryRoot `
        -Cli $fakeCli
    Assert-FileBytes `
        -Expected $preservedBytes `
        -Path $cachePath `
        -Message "An empty export replaced the last valid environment cache"
    Assert-NoTemporaryCaches -Root $temporaryRoot

    $readyDirectory = Join-Path $temporaryRoot "ready"
    $releasePath = Join-Path $temporaryRoot "release"
    New-Item -ItemType Directory -Path $readyDirectory | Out-Null
    [System.IO.File]::Delete($cachePath)

    $concurrentSources = @()
    foreach ($index in 1..4) {
        $candidatePath = Join-Path $temporaryRoot ("concurrent-{0}.yaml" -f $index)
        $candidateYaml = ('RUN_ID: "candidate-{0}"' -f $index) + "`n" +
            ('PAYLOAD: "{0}"' -f ("x" * (4096 + $index))) + "`n"
        [System.IO.File]::WriteAllText($candidatePath, $candidateYaml, $utf8WithoutBom)
        $concurrentSources += $candidatePath
    }

    $jobs = @(
        foreach ($source in $concurrentSources) {
            Start-Job -ScriptBlock {
                param(
                    $SyncScript,
                    $Root,
                    $Cli,
                    $Source,
                    $ReadyDirectory,
                    $Release
                )

                $ErrorActionPreference = "Stop"
                $env:FAKE_INFISICAL_MODE = "success"
                $env:FAKE_INFISICAL_SOURCE = $Source
                $env:FAKE_INFISICAL_READY_DIRECTORY = $ReadyDirectory
                $env:FAKE_INFISICAL_RELEASE_PATH = $Release
                & $SyncScript -ProjectRoot $Root -InfisicalExecutable $Cli
            } -ArgumentList `
                $scriptUnderTest, `
                $temporaryRoot, `
                $fakeCli, `
                $source, `
                $readyDirectory, `
                $releasePath
        }
    )

    $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
    while (@(Get-ChildItem -Path $readyDirectory -Filter "*.ready" -File).Count -lt
        $concurrentSources.Count) {
        if ([DateTime]::UtcNow -ge $readyDeadline) {
            throw "Concurrent fake exports did not all reach the replacement barrier"
        }
        if (@($jobs | Where-Object { $_.State -eq "Failed" }).Count -ne 0) {
            throw "A concurrent synchronization failed before cache replacement"
        }
        Start-Sleep -Milliseconds 25
    }

    [System.IO.File]::WriteAllText($releasePath, "")
    $completedJobs = @($jobs | Wait-Job -Timeout 15)
    if ($completedJobs.Count -ne $jobs.Count -or
        @($jobs | Where-Object { $_.State -ne "Completed" }).Count -ne 0) {
        $states = ($jobs | ForEach-Object { $_.State }) -join ", "
        throw "Concurrent environment synchronization did not complete successfully: $states"
    }
    $jobs | Receive-Job -ErrorAction Stop | Out-Null

    $finalBytes = [System.IO.File]::ReadAllBytes($cachePath)
    $matchesCompleteExport = $false
    foreach ($source in $concurrentSources) {
        $candidateBytes = [System.IO.File]::ReadAllBytes($source)
        if (Test-ByteArraysEqual -Left $candidateBytes -Right $finalBytes) {
            $matchesCompleteExport = $true
            break
        }
    }
    if (-not $matchesCompleteExport) {
        throw "Concurrent synchronization installed a partial or unknown environment cache"
    }
    Assert-NoTemporaryCaches -Root $temporaryRoot
}
finally {
    if ($releasePath) {
        try {
            [System.IO.File]::WriteAllText($releasePath, "")
        }
        catch {}
    }
    if ($jobs.Count -ne 0) {
        $jobs | Stop-Job -ErrorAction SilentlyContinue
        $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
    }
    foreach ($name in $environmentVariableNames) {
        [System.Environment]::SetEnvironmentVariable(
            $name,
            $originalEnvironment[$name],
            "Process"
        )
    }
    Remove-Item -Path $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$scriptUnderTest = Join-Path $PSScriptRoot "sync-env.ps1"
$syncRunConfigurationPath = Join-Path $repositoryRoot ".run\Sync Infisical Dev Env.run.xml"
[xml]$syncRunConfiguration = Get-Content -Path $syncRunConfigurationPath -Raw
$interpreterPath = $syncRunConfiguration.component.configuration.option |
    Where-Object { $_.name -eq "INTERPRETER_PATH" } |
    Select-Object -ExpandProperty value
if (-not (Test-Path $interpreterPath -PathType Leaf)) {
    throw "Configured IntelliJ interpreter does not exist: $interpreterPath"
}

$apiRunConfigurationPath = Join-Path $repositoryRoot ".run\MemoryOS API Dev.run.xml"
[xml]$apiRunConfiguration = Get-Content -Path $apiRunConfigurationPath -Raw
$apiConfiguration = $apiRunConfiguration.component.configuration
if ($apiConfiguration.type -ne "SpringBootApplicationConfigurationType") {
    throw "MemoryOS API Dev must remain a direct Spring Boot run configuration"
}
$configImport = $apiConfiguration.envs.env |
    Where-Object { $_.name -eq "SPRING_CONFIG_IMPORT" } |
    Select-Object -ExpandProperty value
if ($configImport -ne 'file:$PROJECT_DIR$/.memoryos-dev.yaml') {
    throw "MemoryOS API Dev must import the synchronized YAML cache"
}
$syncTask = $apiConfiguration.method.option |
    Where-Object { $_.name -eq "RunConfigurationTask" }
if (-not $syncTask -or
    $syncTask.run_configuration_name -ne "Sync Infisical Dev Env" -or
    $syncTask.run_configuration_type -ne "ShConfigurationType") {
    throw "MemoryOS API Dev must synchronize Infisical before launch"
}
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("memoryos-sync-env-" + [guid]::NewGuid())

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    @'
{
  "workspaceId": "test-project",
  "defaultEnvironment": "dev",
  "gitBranchToEnvironmentMapping": {},
  "defaultSecretPath": "/",
  "domain": "https://secret.example.test"
}
'@ | Set-Content -Path (Join-Path $temporaryRoot ".infisical.json") -Encoding utf8

    $fakeCli = Join-Path $temporaryRoot "infisical.ps1"
    @'
if ($env:FAKE_INFISICAL_FAIL -eq "true") { throw "Simulated Infisical failure" }
$outputArgument = $args | Where-Object { $_.StartsWith("--output-file=") } | Select-Object -First 1
if (-not $outputArgument) { throw "Missing output-file argument" }
$outputPath = $outputArgument.Substring("--output-file=".Length)
if ($args -notcontains "--format=yaml") { throw "Expected YAML export format" }
@(
    'MEMORYOS_DATABASE_URL: "jdbc:postgresql://127.0.0.1:15555/memoryos"'
    'MEMORYOS_SESSION_COOKIE_SECURE: "false"'
) | Set-Content -Path $outputPath -Encoding utf8
'@ | Set-Content -Path $fakeCli -Encoding utf8

    'ORIGINAL: "value"' | Set-Content -Path (Join-Path $temporaryRoot ".memoryos-dev.yaml") -Encoding utf8
    & $scriptUnderTest -ProjectRoot $temporaryRoot -InfisicalExecutable $fakeCli

    $environment = Get-Content -Path (Join-Path $temporaryRoot ".memoryos-dev.yaml")
    if ($environment.Count -ne 2) { throw "Expected two synchronized variables" }
    if ($environment[0] -notlike "MEMORYOS_DATABASE_URL:*") { throw "Database URL was not synchronized" }
    if ($environment[1] -ne 'MEMORYOS_SESSION_COOKIE_SECURE: "false"') { throw "Cookie setting was not synchronized" }
    if (Test-Path (Join-Path $temporaryRoot ".memoryos-dev.yaml.tmp")) { throw "Temporary environment file was retained" }

    $env:FAKE_INFISICAL_FAIL = "true"
    $failed = $false
    try {
        & $scriptUnderTest -ProjectRoot $temporaryRoot -InfisicalExecutable $fakeCli 2>$null
    }
    catch {
        $failed = $true
    }
    if (-not $failed) { throw "Expected failed CLI invocation" }
    $environmentAfterFailure = Get-Content -Path (Join-Path $temporaryRoot ".memoryos-dev.yaml")
    if (Compare-Object $environment $environmentAfterFailure) { throw "Failed sync replaced the last valid environment" }
}
finally {
    Remove-Item Env:FAKE_INFISICAL_FAIL -ErrorAction SilentlyContinue
    Remove-Item -Path $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

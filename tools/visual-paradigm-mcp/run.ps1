param(
    [Parameter(Position = 0)]
    [ValidateSet("build", "test", "package", "install", "sidecar", "clean", "help")]
    [string]$Command = "help",

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"
$gradle = Join-Path $PSScriptRoot "gradlew.bat"

switch ($Command) {
    "build"   { & $gradle clean build @GradleArgs }
    "test"    { & $gradle test @GradleArgs }
    "package" { & $gradle :vp-bridge-plugin:packagePlugin :mcp-server:bootJar @GradleArgs }
    "install" { & $gradle :vp-bridge-plugin:installPlugin @GradleArgs }
    "sidecar" { & $gradle :mcp-server:bootRun @GradleArgs }
    "clean"   { & $gradle clean @GradleArgs }
    default   { Write-Host "Usage: .\run.ps1 {build|test|package|install|sidecar|clean}" }
}

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

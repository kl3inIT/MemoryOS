[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaExecutable,
    [Parameter(Mandatory)][string]$ArgumentsFile,
    [Parameter(Mandatory)][string]$ScratchDirectory,
    [Parameter(Mandatory)][string]$ReportPath
)

# Test-only Windows sampler. The caller owns the fixture, arguments, scratch and reports.
$ErrorActionPreference = 'Stop'
$mem81Start = [System.Diagnostics.ProcessStartInfo]::new()
$mem81Start.FileName = (Resolve-Path -LiteralPath $JavaExecutable).Path
$mem81Start.Arguments = '@"' + (Resolve-Path -LiteralPath $ArgumentsFile).Path + '"'
$mem81Start.UseShellExecute = $false
$mem81Start.CreateNoWindow = $true
$mem81Process = [System.Diagnostics.Process]::Start($mem81Start)
$mem81Clock = [System.Diagnostics.Stopwatch]::StartNew()
$mem81Samples = 0
$mem81WorkingSet = 0L
$mem81Private = 0L
$mem81Temp = 0L
$mem81TempFiles = 0
$mem81PeakFileNames = @()
$mem81Exit = -1
try {
    while (-not $mem81Process.HasExited) {
        if ($mem81Clock.Elapsed.TotalSeconds -gt 150) { throw 'Resource probe exceeded 150 seconds' }
        $mem81Process.Refresh()
        if ($mem81Process.HasExited) { break }
        $mem81WorkingSet = [Math]::Max($mem81WorkingSet, $mem81Process.WorkingSet64)
        $mem81Private = [Math]::Max($mem81Private, $mem81Process.PrivateMemorySize64)
        $mem81Files = @(Get-ChildItem -LiteralPath $ScratchDirectory -File -Recurse -ErrorAction SilentlyContinue)
        $mem81Bytes = ($mem81Files | Measure-Object -Property Length -Sum).Sum
        if ([long]$mem81Bytes -gt $mem81Temp) {
            $mem81Temp = [long]$mem81Bytes
            $mem81PeakFileNames = @($mem81Files | ForEach-Object { $_.Name })
        }
        $mem81TempFiles = [Math]::Max($mem81TempFiles, $mem81Files.Count)
        $mem81Samples++
        Start-Sleep -Milliseconds 50
    }
    $mem81Process.WaitForExit()
    $mem81Exit = $mem81Process.ExitCode
} finally {
    if (-not $mem81Process.HasExited) { $mem81Process.Kill() }
    $mem81Clock.Stop()
    [ordered]@{
        samples = $mem81Samples
        samplingTargetMs = 50
        processId = $mem81Process.Id
        peakWorkingSetBytes = $mem81WorkingSet
        peakPrivateCommitBytes = $mem81Private
        peakTempBytes = $mem81Temp
        peakTempFiles = $mem81TempFiles
        peakTempFileNames = $mem81PeakFileNames
        remainingEntries = @(Get-ChildItem -LiteralPath $ScratchDirectory -Recurse | ForEach-Object { [ordered]@{ name = $_.Name; isDirectory = $_.PSIsContainer; bytes = $_.Length } })
        processElapsedMs = $mem81Clock.ElapsedMilliseconds
        exitCode = $mem81Exit
    } | ConvertTo-Json | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    $mem81Process.Dispose()
}
exit $mem81Exit

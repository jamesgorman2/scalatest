# Runs every test suite the feature review needs, one build-tool session at a time, so that each
# stage starts with a fresh JVM. A failing stage does not stop the stages after it.
#
# Usage, from PowerShell:  .\test-all.ps1 [path-to-sbt]
#        from cmd:         powershell -ExecutionPolicy Bypass -File test-all.ps1 [path-to-sbt]
#
# Logs go to test-all-logs\, one per stage, and test-all-logs\summary.txt lists each stage's exit code.
# The script exits 1 if any stage failed.

param([string]$Sbt = 'sbt')

$root = $PSScriptRoot
$logs = Join-Path $root 'test-all-logs'
$summary = Join-Path $logs 'summary.txt'

# A Scala Native test executable otherwise reserves heap sized to the machine's RAM up front.
if (-not $env:GC_MAXIMUM_HEAP_SIZE) { $env:GC_MAXIMUM_HEAP_SIZE = '1g' }
if (-not $env:GC_INITIAL_HEAP_SIZE) { $env:GC_INITIAL_HEAP_SIZE = '64m' }

New-Item -ItemType Directory -Force $logs | Out-Null
Set-Content -Path $summary -Value @() -Encoding utf8
$script:failed = $false

function Write-Summary([string]$Line) {
  Write-Host $Line
  Add-Content -Path $summary -Value $Line -Encoding utf8
}

function Invoke-Stage([string]$Name, [string]$WorkDir, [string]$Command, [string[]]$Arguments) {
  Write-Host "=== ${Name}: $Command $($Arguments -join ' ')"
  Push-Location $WorkDir
  try {
    & $Command @Arguments 2>&1 | ForEach-Object { "$_" } | Tee-Object -Variable output | Out-Host
    $code = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  Set-Content -Path (Join-Path $logs "$Name.log") -Value $output -Encoding utf8
  Write-Summary "EXIT $code  $Name"
  if ($code -ne 0) { $script:failed = $true }
  return ($code -eq 0)
}

$mima = 'all scalatestCompatible/mimaReportBinaryIssues scalacticDotty/mimaReportBinaryIssues scalatestDotty/mimaReportBinaryIssues scalacticDottyJS/mimaReportBinaryIssues scalatestDottyJS/mimaReportBinaryIssues scalacticDottyNative/mimaReportBinaryIssues scalatestDottyNative/mimaReportBinaryIssues'

$null = Invoke-Stage 'mima' $root $Sbt @($mima)
$null = Invoke-Stage 'scalactic-jvm' $root $Sbt @('scalacticTestDotty/test')
$null = Invoke-Stage 'scalatest-jvm' $root $Sbt @('scalatestTestDotty/test')
$null = Invoke-Stage 'consolidation' $root $Sbt @('scalatestConsolidation/test')
$null = Invoke-Stage 'scalactic-js' $root $Sbt @('scalacticTestDottyJS/test')
$null = Invoke-Stage 'scalatest-js' $root $Sbt @('scalatestTestDottyJS/test')
$null = Invoke-Stage 'scalactic-native' $root $Sbt @('scalacticTestDottyNative/test')
$null = Invoke-Stage 'scalatest-native' $root $Sbt @('scalatestTestDottyNative/test')

# The plugin's Mill build resolves the fork from the local repository.
if (Invoke-Stage 'publish-local' $root $Sbt @('scalatestCompatible/publishLocal', 'scalacticDotty/publishLocal', 'scalatestDotty/publishLocal')) {
  $null = Invoke-Stage 'mill-plugin' (Join-Path $root 'mill-plugin') (Join-Path $root 'mill-plugin\mill.bat') @('--no-daemon', 'plugin.test.testForked', '+', 'plugin.integration.testForked')
} else {
  Write-Summary 'EXIT skipped  mill-plugin (publish-local failed)'
}

Write-Host ''
Write-Host 'Summary:'
Get-Content $summary
if ($script:failed) { exit 1 } else { exit 0 }

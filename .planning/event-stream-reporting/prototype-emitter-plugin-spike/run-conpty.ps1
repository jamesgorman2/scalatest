# PROTOTYPE - throwaway. The console cases under a Windows pseudo-console (ConPTY), with Mill's prompt
# live. Each case's bytes, rendering and summary go to runs\conpty-*; runs\conpty-summary.txt lists all.
# Usage: .\run-conpty.ps1 -Python <python.exe with pywinpty, pyte and psutil installed>
param([Parameter(Mandatory = $true)][string]$Python)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item runs\conpty-* -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"   # warm up; broken.test.compile fails by design

foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq "nodaemon") { "--no-daemon" } else { "" }
  foreach ($c in @('raw', 'log')) {
    foreach ($p in @('lines', 'cr')) {
      & $Python conpty-run.py "$mode-$c-$p" $c $p -- $flag spike.test alpha.test beta.test
    }
  }
}
# Ctrl-C written to the pseudo-console 8 s into the run.
& $Python conpty-run.py 'ctrl-c-daemon' raw lines --ctrl-c 8 -- spike.test slow.test
& $Python conpty-run.py 'ctrl-c-nodaemon' raw lines --ctrl-c 8 -- --no-daemon spike.test slow.test
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"

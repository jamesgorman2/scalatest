# PROTOTYPE - throwaway. Every case in cases.tsv in Git Bash's MinTTY with its default MSYS settings,
# which give Windows programs a pseudo-console, 160 by 40, under the daemon and --no-daemon. Each case
# runs `./mill` (MILL_VERSION=1.1.10-jvm) in a hidden MinTTY window through mintty-run.py; outputs go
# to runs\mintty-*.
# Usage: .\run-mintty.ps1 -Python <python.exe with pyte installed> [-Rows 40]
param([Parameter(Mandatory = $true)][string]$Python, [int]$Rows = 40)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item -Recurse runs\mintty-* -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat jvm.test.compile 1>NUL 2>NUL"
foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
  foreach ($line in Get-Content cases.tsv) {
    $outcome, $kind, $route, $millArgs = $line.Split("`t")
    $fail = if ($outcome -eq 'fail') { '1' } else { '0' }
    & $Python mintty-run.py "mintty-$mode-$outcome-$kind-$route" $Rows $fail -- $flag $millArgs.Split(' ')
  }
}
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"

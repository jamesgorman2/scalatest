# PROTOTYPE - throwaway. Every case in cases.tsv under a Windows pseudo-console (ConPTY), 160 by 40,
# under the daemon and --no-daemon. Outputs go to runs\conpty-*; analyse.py reads them.
# Usage: .\run-conpty.ps1 -Python <python.exe with pywinpty and pyte installed> [-Rows 40]
param([Parameter(Mandatory = $true)][string]$Python, [int]$Rows = 40)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item -Recurse runs\conpty-* -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat jvm.test.compile 1>NUL 2>NUL"
foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
  foreach ($line in Get-Content cases.tsv) {
    $outcome, $kind, $route, $millArgs = $line.Split("`t")
    $fail = if ($outcome -eq 'fail') { '1' } else { '0' }
    & $Python conpty-run.py "conpty-$mode-$outcome-$kind-$route" $Rows $fail -- $flag $millArgs.Split(' ')
  }
}
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"

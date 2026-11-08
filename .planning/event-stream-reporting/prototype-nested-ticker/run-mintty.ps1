# PROTOTYPE - throwaway. The cases in Git Bash's MinTTY with its default MSYS settings, which give
# Windows programs a pseudo-console. Each case runs `./mill` (MILL_VERSION=1.1.10-jvm) in a hidden
# MinTTY window through mintty-run.py; outputs go to runs\mintty-*, and runs\summary.txt collects
# every case's block.
# Usage: .\run-mintty.ps1 -Python <python.exe with pyte installed> [-Repeats 3] [-Msys default]
param([Parameter(Mandatory = $true)][string]$Python, [int]$Repeats = 3, [string]$Msys = 'default')
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
$tag = if ($Msys -eq 'default') { 'mintty' } else { "mintty-$Msys" }
Remove-Item "runs\$tag-*" -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"   # warm up; broken.test.compile fails by design

$wide = 'nt.test a.test b.test c.test d.test e.test g.test h.test failing.test broken.test'
foreach ($r in 1..$Repeats) {
  foreach ($mode in @('daemon', 'nodaemon')) {
    $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
    & $Python mintty-run.py "$tag-$mode-copy-$r" copy 40 $Msys -- $flag $wide.Split(' ')
  }
}
foreach ($route in @('copy', 'copy-logger', 'copy-base')) {
  & $Python mintty-run.py "$tag-daemon-$route-rows24" $route 24 $Msys -- $wide.Split(' ')
  & $Python mintty-run.py "$tag-daemon-$route-tiny" $route 40 $Msys -- nt.tiny
}
foreach ($route in @('copy-logger', 'copy-base')) {
  & $Python mintty-run.py "$tag-daemon-$route-1" $route 40 $Msys -- $wide.Split(' ')
}
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"

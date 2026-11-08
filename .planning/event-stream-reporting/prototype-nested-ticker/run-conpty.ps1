# PROTOTYPE - throwaway. The cases under a Windows pseudo-console (ConPTY), with Mill's prompt live.
# Each case's chunks, records, timeline, rendering and summary go to runs\conpty-*; runs\summary.txt
# collects every case's block.
# Usage: .\run-conpty.ps1 -Python <python.exe with pywinpty, pyte and psutil installed> [-Repeats 3]
param([Parameter(Mandatory = $true)][string]$Python, [int]$Repeats = 3)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item runs\conpty-* -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"   # warm up; broken.test.compile fails by design

$wide = 'nt.test a.test b.test c.test d.test e.test g.test h.test failing.test broken.test'
foreach ($r in 1..$Repeats) {
  foreach ($mode in @('daemon', 'nodaemon')) {
    $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
    & $Python conpty-run.py "conpty-$mode-copy-$r" copy 40 -- $flag $wide.Split(' ')
  }
}
# Alternative routes, a short terminal (Mill shows at most rows/3 - 1 task lines) and a nested
# evaluation of one task (key 1), under the daemon.
foreach ($route in @('copy', 'copy-logger', 'copy-base')) {
  & $Python conpty-run.py "conpty-daemon-$route-rows24" $route 24 -- $wide.Split(' ')
  & $Python conpty-run.py "conpty-daemon-$route-tiny" $route 40 -- nt.tiny
}
foreach ($route in @('copy-logger', 'copy-base')) {
  & $Python conpty-run.py "conpty-daemon-$route-1" $route 40 -- $wide.Split(' ')
}
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"

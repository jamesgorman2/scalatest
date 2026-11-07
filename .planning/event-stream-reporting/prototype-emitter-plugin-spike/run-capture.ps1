# PROTOTYPE - throwaway. Parallel worker capture (https://github.com/jamesgorman2/scalatest/issues/42)
# on Windows: runs gamma.test's testForked, routeCtx (Route A) and routeQueue (Route B) with -j 3,
# passing and with a failing test, under the daemon and --no-daemon, for N rounds. Saves each run's
# console output, exit status, Task.dest and task JSON under runs\capture\windows\, then analyses.
# Usage: .\run-capture.ps1 [rounds, default 3]
param([int]$Rounds = 3)
Set-Location $PSScriptRoot
$root = 'runs\capture\windows'
if (Test-Path $root) { Remove-Item -Recurse -Force $root }
New-Item -ItemType Directory -Force $root | Out-Null

cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat gamma.test.compile 1>NUL 2>NUL"

foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
  foreach ($fail in @('pass', 'fail')) {
    if ($fail -eq 'fail') { $env:SPIKE_FAIL = '1' } else { Remove-Item Env:\SPIKE_FAIL -ErrorAction SilentlyContinue }
    for ($r = 1; $r -le $Rounds; $r++) {
      foreach ($cmd in @('testForked', 'routeCtx', 'routeQueue')) {
        $name = "$mode-$fail-$cmd-$r"
        Remove-Item "out\gamma\test\$cmd.json" -ErrorAction SilentlyContinue
        cmd /c ".\mill.bat $flag -j 3 gamma.test.$cmd 1>$root\$name.out 2>$root\$name.err"
        $code = $LASTEXITCODE
        Set-Content -Encoding ascii "$root\$name.exit" $code
        Write-Host "$name exit=$code"
        robocopy "out\gamma\test\$cmd.dest" "$root\$name-dest" /E /NFL /NDL /NJH /NJS /NP | Out-Null
        if (Test-Path "out\gamma\test\$cmd.json") { Copy-Item "out\gamma\test\$cmd.json" "$root\$name.json" }
      }
    }
  }
}
Remove-Item Env:\SPIKE_FAIL -ErrorAction SilentlyContinue
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
python analyse-capture.py $root

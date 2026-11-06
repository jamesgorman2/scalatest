# PROTOTYPE - throwaway. Runs the non-interactive matrix on Windows and saves each command's console
# output under runs\ (<name>.out, <name>.err, <name>-events.tsv; exit codes in runs\exits.txt).
# Usage: .\run.ps1
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item runs\* -ErrorAction SilentlyContinue

function Run($name, $millArgs, $console = 'raw') {
  $env:SPIKE_CONSOLE = $console
  cmd /c ".\mill.bat $millArgs 1>runs\$name.out 2>runs\$name.err"
  Write-Host "$name exit=$LASTEXITCODE"
  Add-Content -Encoding utf8 runs\exits.txt "$name exit=$LASTEXITCODE"
  if (Test-Path out\spike\test.dest\events.tsv) {
    Move-Item -Force out\spike\test.dest\events.tsv "runs\$name-events.tsv"
  }
}

$all = 'alpha.test beta.test crash.test broken.test late.test'
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"   # warm up; broken.test.compile fails by design

# 1-3: lifetime, results, exit status, live events, killed workers
Run 'daemon-all' "spike.test $all"
Run 'daemon-all-keepgoing' "--keep-going spike.test $all"
Run 'daemon-alpha' 'spike.test alpha.test'
# a failing sibling goal on the same command line
Run 'daemon-sibling-fail' 'beta.test + spike.test alpha.test'
Run 'daemon-sibling-fail-keepgoing' '--keep-going beta.test + spike.test alpha.test'
# 4: selectors as separate arguments, and `+`
Run 'daemon-plus' 'spike.test beta.test + alpha.test'

# 7: console, raw streams versus Task.log streams, under the daemon and --no-daemon
foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
  foreach ($c in @('raw', 'log')) {
    Run "$mode-console-$c" "$flag spike.test alpha.test beta.test" $c
  }
}

# 5: worker streams
Run 'daemon-capture-direct' 'beta.test.captureDirect beta.BetaOk'
Run 'nodaemon-capture-direct' '--no-daemon beta.test.captureDirect beta.BetaOk'
Run 'daemon-withstreams-parallel' 'alpha.test.withStreamsRunTests'
Run 'daemon-shim' 'shimmed.test'
Get-ChildItem out\shimmed\test\testForked.dest -Recurse -Filter 'shim-*.txt' |
  ForEach-Object { Copy-Item $_.FullName "runs\daemon-shim-$($_.Name)" }

# 6: testLogLevel
Run 'daemon-loglevel-debug' 'loud.test'
Run 'daemon-loglevel-error' 'quiet.test'

cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
Remove-Item Env:\SPIKE_CONSOLE
$left = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*MillTestRunnerMain*' }
if ($left) { Add-Content -Encoding utf8 runs\exits.txt "ORPHANED TEST WORKERS: $($left.ProcessId -join ',')" }

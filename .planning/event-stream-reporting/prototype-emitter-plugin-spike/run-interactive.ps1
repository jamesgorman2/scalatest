# PROTOTYPE - throwaway. The console cases that need a real terminal, one at a time.
# Run it yourself in a real Windows terminal (Windows Terminal or a console window), not redirected:
#   powershell -ExecutionPolicy Bypass -File .\run-interactive.ps1
# Each case says what to look for, runs one Mill command, then asks for your answers.
# Answers go to runs\interactive-answers.txt.
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
$answers = 'runs\interactive-answers.txt'
"run-interactive.ps1 started $(Get-Date -Format o)" | Out-File -Append -Encoding utf8 $answers

function Ask($case, $question) {
  $a = Read-Host "  $question"
  "$case | $question | $a" | Out-File -Append -Encoding utf8 $answers
}

function Pause-Before($text) {
  Write-Host ''
  Write-Host ('=' * 78) -ForegroundColor Cyan
  Write-Host $text
  Write-Host ('=' * 78) -ForegroundColor Cyan
  Read-Host 'Press Enter to start this case' | Out-Null
}

$lookFor = @'
What the command prints itself:
  - "SPIKE ..." lines and "PROGRESS +<time> ..." lines (one per event, or one line rewritten in place).
What Mill prints:
  - its prompt: a header line with a task count and elapsed time, and one line per running task,
    such as "[190-0-0] alpha.test.testForked ..." and "[...] slow.test.testForked ...", redrawn
    continuously at the bottom of the screen while tests run;
  - worker output, prefixed by a task key, such as "190-0-0] MARK-OUT A1 t1 ...".
Look for:
  (a) a SPIKE or PROGRESS line broken in two, or with prompt text glued into it;
  (b) prompt lines left behind in the scrollback instead of being cleared (duplicated
      "[...] ...testForked" lines between output lines);
  (c) SPIKE or PROGRESS lines overwritten or erased by the prompt redraw;
  (d) whether SPIKE or PROGRESS lines carry a "[1]" or other prefix.
'@

$cases = @(
  @{ n = 'daemon-raw-lines';   flag = '';            c = 'raw'; p = 'lines' },
  @{ n = 'daemon-log-lines';   flag = '';            c = 'log'; p = 'lines' },
  @{ n = 'daemon-raw-cr';      flag = '';            c = 'raw'; p = 'cr' },
  @{ n = 'daemon-log-cr';      flag = '';            c = 'log'; p = 'cr' },
  @{ n = 'nodaemon-raw-lines'; flag = '--no-daemon'; c = 'raw'; p = 'lines' },
  @{ n = 'nodaemon-log-lines'; flag = '--no-daemon'; c = 'log'; p = 'lines' },
  @{ n = 'nodaemon-raw-cr';    flag = '--no-daemon'; c = 'raw'; p = 'cr' },
  @{ n = 'nodaemon-log-cr';    flag = '--no-daemon'; c = 'log'; p = 'cr' }
)

cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"   # warm up; broken.test.compile fails by design

foreach ($k in $cases) {
  $env:SPIKE_CONSOLE = $k.c
  $env:SPIKE_PROGRESS = $k.p
  $how = if ($k.c -eq 'raw') { "the command's raw streams" } else { "the command's Task.log streams" }
  $prog = if ($k.p -eq 'cr') {
    'ONE progress line, rewritten in place with a carriage return on each event'
  } else { 'one new PROGRESS line per event' }
  Pause-Before @"
CASE $($k.n): mill $($k.flag) spike.test alpha.test beta.test slow.test
The command writes its output through $how, as $prog.
It runs about 15 seconds.
$lookFor
"@
  cmd /c ".\mill.bat $($k.flag) spike.test alpha.test beta.test slow.test"
  "$($k.n) exit=$LASTEXITCODE" | Out-File -Append -Encoding utf8 $answers
  Ask $k.n '(a) Any SPIKE/PROGRESS line broken or with prompt text glued in? (y/n, notes)'
  Ask $k.n '(b) Prompt lines left behind in the scrollback? (y/n, notes)'
  Ask $k.n '(c) SPIKE/PROGRESS lines overwritten or erased by the prompt? (y/n, notes)'
  Ask $k.n '(d) Prefix on SPIKE/PROGRESS lines? (none / which)'
  if ($k.p -eq 'cr') { Ask $k.n '(e) Did the single progress line stay one line updated in place? (y/n, notes)' }
}
Remove-Item Env:\SPIKE_PROGRESS -ErrorAction SilentlyContinue
$env:SPIKE_CONSOLE = 'raw'

# Ctrl-C under the daemon. Runs in a new console window so that Ctrl-C does not stop this script.
Pause-Before @'
CASE daemon-ctrl-c: mill spike.test slow.test, interrupted
A new console window opens and runs the command under the daemon.
Wait until at least two "SPIKE conn#... hello" lines and some PROGRESS lines have appeared,
then press Ctrl-C once in THAT window. If cmd asks "Terminate batch job (Y/N)?", answer N.
Look for:
  (a) whether "SPIKE execute-returned", "SPIKE ===== summary =====" or "SPIKE body-end" appear
      after Ctrl-C;
  (b) how long the window takes to return to the prompt or print its final line;
  (c) the EXIT= value printed at the end.
Then press a key to close that window. This script then lists surviving test-worker JVMs.
'@
Start-Process cmd -ArgumentList '/c', '.\mill.bat spike.test slow.test & echo EXIT=%ERRORLEVEL% & pause' -Wait
$workers = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*MillTestRunnerMain*' }
$daemons = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*MillDaemonMain*' }
"daemon-ctrl-c test workers alive after window closed: $(@($workers).Count) $($workers.ProcessId -join ',')" |
  Out-File -Append -Encoding utf8 $answers
"daemon-ctrl-c Mill daemons alive after window closed: $(@($daemons).Count) $($daemons.ProcessId -join ',')" |
  Out-File -Append -Encoding utf8 $answers
Ask 'daemon-ctrl-c' '(a) Which SPIKE lines appeared after Ctrl-C? (none / list)'
Ask 'daemon-ctrl-c' '(b) How long until the final line? (seconds)'
Ask 'daemon-ctrl-c' '(c) EXIT= value'
Ask 'daemon-ctrl-c' 'Anything else printed after Ctrl-C? (notes)'
Get-ChildItem out\mill-daemon -Recurse -Include stdout, stderr -ErrorAction SilentlyContinue |
  ForEach-Object { Copy-Item $_.FullName "runs\interactive-ctrl-c-daemon-$($_.Name).txt" }
Write-Host "Done. Answers are in $answers; send that file back."

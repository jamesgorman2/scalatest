# PROTOTYPE - throwaway. Runs the matrix on Windows into runs\windows\, then analyses it.
# Usage: .\run.ps1 -LlvmBin <dir holding clang.exe>
param([Parameter(Mandatory = $true)][string]$LlvmBin)

Set-Location $PSScriptRoot
$env:LLVM_BIN = $LlvmBin
$env:PATH = "$LlvmBin;$env:PATH"
$root = 'runs\windows'
Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $root | Out-Null

function Run($name, $millArgs, $dests) {
  cmd /c ".\mill.bat $millArgs 1>$root\$name.out 2>$root\$name.err"
  "$LASTEXITCODE" | Out-File -Encoding ascii "$root\$name.exit"
  "$name exit=$LASTEXITCODE"
  $i = 0
  foreach ($d in $dests) {
    if (Test-Path $d) { Copy-Item -Recurse $d "$root\$name-dest$i" }
    $i++
  }
}

cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
cmd /c ".\mill.bat __.compile 1>NUL 2>NUL"

$routes = @('public', 'member', 'hook', 'command')
foreach ($mode in @('daemon', 'nodaemon')) {
  $flag = if ($mode -eq 'nodaemon') { '--no-daemon' } else { '' }
  foreach ($outcome in @('pass', 'fail')) {
    if ($outcome -eq 'fail') { $env:SPIKE_FAIL = '1' } else { Remove-Item Env:SPIKE_FAIL -ErrorAction SilentlyContinue }
    Run "$mode-$outcome-jvm-control" "$flag -j 3 jvm.test.testForked" @('out\jvm\test\testForked.dest')
    foreach ($r in $routes) {
      Run "$mode-$outcome-jvm-$r" "$flag -j 3 notrait.run --route $r jvm.test" @("out\notrait\modules\$r\jvm.test")
    }
  }
  Remove-Item Env:SPIKE_FAIL -ErrorAction SilentlyContinue

  # Class globs, as testOnly takes them.
  Run "$mode-pass-glob-control" "$flag -j 3 jvm.test.testOnly gamma.G0*" @('out\jvm\test\testOnly.dest')
  $env:NOTRAIT_GLOBS = 'gamma.G0*'
  foreach ($r in $routes) {
    Run "$mode-pass-glob-$r" "$flag -j 3 notrait.run --route $r jvm.test" @("out\notrait\modules\$r\jvm.test")
  }
  Remove-Item Env:NOTRAIT_GLOBS

  # Scala.js and Native.
  Run "$mode-pass-x-control" "$flag -j 3 js.test.testForked + native.test.testForked" @('out\js\test\testForked.dest', 'out\native\test\testForked.dest')
  foreach ($r in @('hook', 'command')) {
    Run "$mode-pass-x-$r" "$flag -j 3 notrait.run --route $r js.test native.test" @("out\notrait\modules\$r\js.test", "out\notrait\modules\$r\native.test")
  }
  Run "$mode-pass-x-adapter" "$flag -j 3 notrait.run --route adapter native.test" @('out\notrait\modules\adapter\native.test')

  # Every runtime, and the Zinc-discovery module, in one command.
  Run "$mode-pass-all-command" "$flag -j 3 notrait.run --route command jvm.test zinc.test js.test native.test" @('out\notrait\modules\command')
}
cmd /c ".\mill.bat shutdown 1>NUL 2>NUL"
python analyse.py $root

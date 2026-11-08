#!/bin/bash
# PROTOTYPE - throwaway. Runs the matrix on Linux into runs/linux/, then analyses it. Run in a copy of this folder in the WSL file system.
# Usage: ./run.sh            (node on PATH; clang on PATH for Native)
#        NO_NATIVE=1 ./run.sh  leaves out every Native run.
cd "$(dirname "$0")" || exit 1
root=runs/linux
rm -rf "$root" && mkdir -p "$root"

run() { # name, "mill args", dests...
  local name=$1 args=$2; shift 2
  ./mill $args >"$root/$name.out" 2>"$root/$name.err"
  local code=$?
  echo "$code" >"$root/$name.exit"
  echo "$name exit=$code"
  local i=0
  for d in "$@"; do [ -e "$d" ] && cp -r "$d" "$root/$name-dest$i"; i=$((i + 1)); done
}

x=(js.test native.test); [ -n "$NO_NATIVE" ] && x=(js.test)
./mill shutdown >/dev/null 2>&1
./mill jvm.test.compile zinc.test.compile js.test.compile >/dev/null 2>&1

routes="public member hook command"
for mode in daemon nodaemon; do
  flag=""; [ $mode = nodaemon ] && flag="--no-daemon"
  for outcome in pass fail; do
    if [ $outcome = fail ]; then export SPIKE_FAIL=1; else unset SPIKE_FAIL; fi
    run "$mode-$outcome-jvm-control" "$flag -j 3 jvm.test.testForked" out/jvm/test/testForked.dest
    for r in $routes; do
      run "$mode-$outcome-jvm-$r" "$flag -j 3 notrait.run --route $r jvm.test" "out/notrait/modules/$r/jvm.test"
    done
  done
  unset SPIKE_FAIL

  run "$mode-pass-glob-control" "$flag -j 3 jvm.test.testOnly gamma.G0*" out/jvm/test/testOnly.dest
  export NOTRAIT_GLOBS='gamma.G0*'
  for r in $routes; do
    run "$mode-pass-glob-$r" "$flag -j 3 notrait.run --route $r jvm.test" "out/notrait/modules/$r/jvm.test"
  done
  unset NOTRAIT_GLOBS

  ctl=(); for m in "${x[@]}"; do ctl+=("$m.testForked" "+"); done
  dests=(); for m in "${x[@]}"; do dests+=("out/${m%.test}/test/testForked.dest"); done
  run "$mode-pass-x-control" "$flag -j 3 ${ctl[*]:0:${#ctl[@]}-1}" "${dests[@]}"
  for r in hook command; do
    dests=(); for m in "${x[@]}"; do dests+=("out/notrait/modules/$r/$m"); done
    run "$mode-pass-x-$r" "$flag -j 3 notrait.run --route $r ${x[*]}" "${dests[@]}"
  done
  [ -z "$NO_NATIVE" ] && run "$mode-pass-x-adapter" "$flag -j 3 notrait.run --route adapter native.test" out/notrait/modules/adapter/native.test

  run "$mode-pass-all-command" "$flag -j 3 notrait.run --route command jvm.test zinc.test ${x[*]}" out/notrait/modules/command
done
./mill shutdown >/dev/null 2>&1
python3 analyse.py "$root"

#!/bin/bash
# PROTOTYPE - throwaway. Parallel worker capture (https://github.com/jamesgorman2/scalatest/issues/42)
# on Linux: the same matrix as run-capture.ps1, saved under runs/capture/linux/, then analysed.
# Usage: ./run-capture.sh [rounds, default 3] [modes, default "daemon nodaemon"] [outcomes, default "pass fail"]
#        Given modes or outcomes, it keeps earlier runs, so the matrix can be run in parts.
cd "$(dirname "$0")" || exit 1
rounds=${1:-3}
modes=${2:-daemon nodaemon}
outcomes=${3:-pass fail}
root=runs/capture/linux
[ -z "$2$3" ] && rm -rf "$root"
mkdir -p "$root"

./mill shutdown >/dev/null 2>&1
./mill gamma.test.compile >/dev/null 2>&1

for mode in $modes; do
  flag=(); [ $mode = nodaemon ] && flag=(--no-daemon)
  for fail in $outcomes; do
    if [ $fail = fail ]; then export SPIKE_FAIL=1; else unset SPIKE_FAIL; fi
    for r in $(seq 1 "$rounds"); do
      for cmd in testForked routeCtx routeQueue; do
        name=$mode-$fail-$cmd-$r
        rm -f "out/gamma/test/$cmd.json"
        ./mill "${flag[@]}" -j 3 gamma.test.$cmd >"$root/$name.out" 2>"$root/$name.err"
        code=$?
        echo "$code" >"$root/$name.exit"
        echo "$name exit=$code"
        cp -r "out/gamma/test/$cmd.dest" "$root/$name-dest"
        [ -f "out/gamma/test/$cmd.json" ] && cp "out/gamma/test/$cmd.json" "$root/$name.json"
      done
    done
  done
done
unset SPIKE_FAIL
./mill shutdown >/dev/null 2>&1
python3 analyse-capture.py "$root"

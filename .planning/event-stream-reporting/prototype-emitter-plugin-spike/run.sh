#!/bin/bash
# PROTOTYPE - throwaway. Runs the matrix on Linux and saves each command's console output under runs/.
# Also runs the console cases under a pseudo-terminal with `script`, saving the raw terminal bytes
# as runs/pty-*.typescript.
# Usage: ./run.sh            the whole matrix, then the Ctrl-C cases
#        ./run.sh ctrl-c     only the Ctrl-C cases
cd "$(dirname "$0")" || exit 1
mkdir -p runs
[ "$1" = ctrl-c ] || rm -f runs/*

ctrlc() { # Types Ctrl-C into the pseudo-terminal 8 s into `spike.test slow.test`.
  for mode in daemon nodaemon; do
    flag=""; [ $mode = nodaemon ] && flag="--no-daemon"
    local t=runs/pty-ctrl-c-$mode
    (sleep 8; printf '\003'; sleep 20) |
      SPIKE_CONSOLE=raw script -q -e -c "stty cols 160 rows 40; ./mill $flag spike.test slow.test; echo EXIT=\$?" "$t.typescript" >/dev/null 2>&1
    {
      echo "after Ctrl-C + 20 s:"
      echo "test workers: $(pgrep -f MillTestRunnerMain | tr '\n' ' ')"
      echo "mill daemons: $(pgrep -f MillDaemonMain | tr '\n' ' ')"
      grep -a -o 'SPIKE [a-z= #0-9-]*\|EXIT=[0-9]*' "$t.typescript" | sort | uniq -c
    } >"$t.txt"
    echo "pty-ctrl-c-$mode done" | tee -a runs/exits.txt
  done
}
if [ "$1" = ctrl-c ]; then ctrlc; exit; fi

run() { # name, console, mill args...
  local name=$1 console=$2; shift 2
  SPIKE_CONSOLE=$console ./mill "$@" >"runs/$name.out" 2>"runs/$name.err"
  echo "$name exit=$?" | tee -a runs/exits.txt
  [ -f out/spike/test.dest/events.tsv ] && mv out/spike/test.dest/events.tsv "runs/$name-events.tsv"
}

pty() { # name, console, progress, mill args...
  local name=$1 console=$2 progress=$3; shift 3
  SPIKE_CONSOLE=$console SPIKE_PROGRESS=$progress COLUMNS=160 LINES=40 \
    script -q -e -c "stty cols 160 rows 40; ./mill $*" "runs/pty-$name.typescript" >/dev/null 2>&1 </dev/null
  echo "pty-$name exit=$?" | tee -a runs/exits.txt
}

all="alpha.test beta.test crash.test broken.test late.test"
./mill shutdown >/dev/null 2>&1
./mill __.compile >/dev/null 2>&1

run daemon-all raw spike.test $all
run daemon-all-keepgoing raw --keep-going spike.test $all
run daemon-alpha raw spike.test alpha.test
run daemon-sibling-fail raw beta.test + spike.test alpha.test
run daemon-sibling-fail-keepgoing raw --keep-going beta.test + spike.test alpha.test
run daemon-plus raw spike.test beta.test + alpha.test

for mode in daemon nodaemon; do
  flag=(); [ $mode = nodaemon ] && flag=(--no-daemon)
  for c in raw log; do
    run "$mode-console-$c" $c "${flag[@]}" spike.test alpha.test beta.test
  done
done

run daemon-capture-direct raw beta.test.captureDirect beta.BetaOk
run nodaemon-capture-direct raw --no-daemon beta.test.captureDirect beta.BetaOk
run daemon-withstreams-parallel raw alpha.test.withStreamsRunTests
run daemon-shim raw shimmed.test
for f in $(find out/shimmed/test/testForked.dest -name 'shim-*.txt'); do cp "$f" "runs/daemon-shim-$(basename "$f")"; done
run daemon-loglevel-debug raw loud.test
run daemon-loglevel-error raw quiet.test

# Pseudo-terminal: Mill's prompt is live.
for mode in daemon nodaemon; do
  flag=""; [ $mode = nodaemon ] && flag="--no-daemon"
  for c in raw log; do
    pty "$mode-$c-lines" $c lines $flag spike.test alpha.test beta.test
    pty "$mode-$c-cr" $c cr $flag spike.test alpha.test beta.test
  done
done

for f in runs/pty-*.typescript; do python3 render-pty.py "$f"; done > runs/pty-summary.txt

ctrlc

./mill shutdown >/dev/null 2>&1
pgrep -fl MillTestRunnerMain && echo "ORPHANED TEST WORKERS ABOVE" | tee -a runs/exits.txt

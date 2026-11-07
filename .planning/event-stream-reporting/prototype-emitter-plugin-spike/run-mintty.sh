#!/bin/bash
# PROTOTYPE - throwaway. The console cases under Git Bash's MinTTY on Windows: each case starts a
# hidden 160x40 MinTTY window running `bash -lc './mill …'` with MinTTY's --log, waits for it, and
# renders the log with pyte (render-pyte.py). Output: runs/mintty-*.
# Usage (from Git Bash): PYTHON=<python.exe with pyte> ./run-mintty.sh [MSYS setting, e.g. disable_pcon]
# `./mill` 1.1.10 refuses Windows ("supports only Linux and macOS") unless MILL_VERSION ends in -jvm.
cd "$(dirname "$0")" || exit 1
mkdir -p runs
here=$(pwd)
pcon=${1:-}
tag=${pcon:-default}
MINTTY="/c/Program Files/Git/usr/bin/mintty.exe"

case_run() { # name, console, progress, mill args...
  local name=mintty-$tag-$1 console=$2 progress=$3; shift 3
  local log=runs/$name.log
  rm -f "$log" out/spike/test.dest/console.txt
  "$MINTTY" -w hide --hold never -s 160,40 --log "$log" -e /usr/bin/bash -lc \
    "cd '$here' && MSYS=$pcon MILL_VERSION=1.1.10-jvm SPIKE_CONSOLE=$console SPIKE_PROGRESS=$progress ./mill $*; echo MINTTY-EXIT=\$?"
  for _ in $(seq 1 180); do grep -aq 'MINTTY-EXIT=' "$log" 2>/dev/null && break; sleep 1; done
  sleep 1
  local echo=-
  [ -f out/spike/test.dest/console.txt ] && cp out/spike/test.dest/console.txt "runs/$name-console.txt" && echo=runs/$name-console.txt
  echo "$name $(grep -ao 'MINTTY-EXIT=[0-9]*' "$log")" | tee -a runs/mintty-summary.txt
  "$PYTHON" render-pyte.py "$name" "$log" "$echo"
}

MSYS=$pcon MILL_VERSION=1.1.10-jvm ./mill __.compile >/dev/null 2>&1   # warm up; broken.test fails by design
for mode in daemon nodaemon; do
  flag=""; [ $mode = nodaemon ] && flag="--no-daemon"
  for c in raw log; do
    for p in lines cr; do
      case_run "$mode-$c-$p" $c $p $flag spike.test alpha.test beta.test
    done
  done
done

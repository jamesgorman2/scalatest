#!/bin/bash
# PROTOTYPE - throwaway. The cases on Linux under a pseudo-terminal made by `script`, 160 by <rows>;
# tsrec.py stamps each chunk of the terminal's output. Each case's chunks and the command's records go
# to runs/linux-*; analyse.py (which needs pyte) then measures them. run-wsl.ps1 runs this under WSL
# and the analysis on Windows.
# Usage: ./run.sh [repeats]
cd "$(dirname "$0")" || exit 1
mkdir -p runs
rm -f runs/linux-*
repeats=${1:-3}

case_run() { # name, route, rows, mill args...
  local name=linux-$1 route=$2 rows=$3; shift 3
  local cmd; cmd=$(printf '%s\n' "$@" | grep -m1 '^nt\.' | cut -c4-)
  rm -f out/nt/$cmd.dest/{ticker.tsv,console.tsv,console.txt,markers.tsv,keys.tsv}
  printf '{"cols": 160, "rows": %s, "terminal": "linux script", "route": "%s", "args": ["%s"]}\n' \
    "$rows" "$route" "$(echo "$*" | sed 's/ /", "/g')" >"runs/$name.meta.json"
  NT_ROUTE=$route script -q -e -c "stty cols 160 rows $rows; ./mill $*" /dev/null </dev/null |
    python3 tsrec.py "runs/$name.chunks.jsonl"
  echo "$name exit=${PIPESTATUS[0]}" | tee -a runs/linux-exits.txt
  for f in ticker.tsv console.tsv console.txt markers.tsv keys.tsv; do
    [ -f out/nt/$cmd.dest/$f ] && cp out/nt/$cmd.dest/$f "runs/$name-$f"
  done
}

wide="nt.test a.test b.test c.test d.test e.test g.test h.test failing.test broken.test"
./mill shutdown >/dev/null 2>&1
./mill __.compile >/dev/null 2>&1   # warm up; broken.test.compile fails by design
for r in $(seq 1 "$repeats"); do
  for mode in daemon nodaemon; do
    flag=""; [ $mode = nodaemon ] && flag="--no-daemon"
    case_run "$mode-copy-$r" copy 40 $flag $wide
  done
done
for route in copy copy-logger copy-base; do
  case_run "daemon-$route-rows24" $route 24 $wide
  case_run "daemon-$route-tiny" $route 40 nt.tiny
done
for route in copy-logger copy-base; do
  case_run "daemon-$route-1" $route 40 $wide
done
./mill shutdown >/dev/null 2>&1

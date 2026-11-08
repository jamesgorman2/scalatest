#!/bin/bash
# PROTOTYPE - throwaway. Every case in cases.tsv on Linux under a pseudo-terminal made by `script`,
# 160 by <rows>, under the daemon and --no-daemon; tsrec.py stamps each chunk of the terminal's output.
# Outputs go to runs/linux-*. run-wsl.ps1 runs this under WSL; analyse.py runs on Windows.
# Usage: ./run.sh [rows]
cd "$(dirname "$0")" || exit 1
mkdir -p runs
rm -rf runs/linux-*
rows=${1:-40}
./mill shutdown >/dev/null 2>&1
./mill jvm.test.compile >/dev/null 2>&1
for mode in daemon nodaemon; do
  flag=""
  [ $mode = nodaemon ] && flag="--no-daemon"
  while IFS=$'\t' read -r outcome kind route args; do
    name=linux-$mode-$outcome-$kind-$route
    fail=false
    [ "$outcome" = fail ] && fail=true
    set -f
    # shellcheck disable=SC2206
    argv=($flag $args)
    set +f
    json=$(printf '"%s",' "${argv[@]}")
    printf '{"cols": 160, "rows": %s, "terminal": "linux script", "fail": %s, "args": [%s]}\n' \
      "$rows" "$fail" "${json%,}" >"runs/$name.meta.json"
    python3 collect.py before "$name" - -- "${argv[@]}"
    quoted=$(printf "'%s' " "${argv[@]}")
    failenv=""
    [ $fail = true ] && failenv="SPIKE_FAIL=1"
    env -u SPIKE_FAIL $failenv script -q -e -c "stty cols 160 rows $rows; ./mill $quoted" /dev/null </dev/null |
      python3 tsrec.py "runs/$name.chunks.jsonl"
    code=${PIPESTATUS[0]}
    echo "$name exit=$code"
    python3 collect.py after "$name" "$code" -- "${argv[@]}"
  done <cases.tsv
done
./mill shutdown >/dev/null 2>&1

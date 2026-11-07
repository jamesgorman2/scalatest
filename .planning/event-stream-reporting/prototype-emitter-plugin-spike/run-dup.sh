#!/bin/bash
# PROTOTYPE - throwaway. Counts runs in which a test class ran on two workers, with and without the
# spike's command, under --no-daemon: Git Bash `./mill` and `cmd /c mill.bat`, output redirected.
# Keeps each run's claim folders and claim.log files under runs/dup/<case>-<n>/.
# Usage (from Git Bash): ./run-dup.sh [runs per case, default 8]
cd "$(dirname "$0")" || exit 1
n=${1:-8}
mkdir -p runs/dup && rm -rf runs/dup/* && rm -f runs/dup-summary.txt

one() { # case name, launcher (gitbash|bat), mill args...
  local name=$1 how=$2; shift 2
  for i in $(seq 1 "$n"); do
    local d=runs/dup/$name-$i
    mkdir -p "$d"
    if [ "$how" = gitbash ]; then
      MILL_VERSION=1.1.10-jvm SPIKE_CONSOLE=raw ./mill --no-daemon "$@" >"$d/out.txt" 2>"$d/err.txt"
    else
      SPIKE_CONSOLE=raw cmd //c ".\\mill.bat --no-daemon $* 1>$(cygpath -w "$d")\\out.txt 2>$(cygpath -w "$d")\\err.txt"
    fi
    local rc=$?
    # Copy what Mill left in each worker folder: claim/ (claimed class files) and claim.log.
    for m in alpha beta; do
      local src=out/$m/test/testForked.dest
      [ -d "$src" ] || continue
      (cd "$src" && find . -path "*/claim/*" -o -name claim.log -o -path "*/test-classes/*") | while read -r f; do
        mkdir -p "$d/$m/$(dirname "$f")"; cp "$src/$f" "$d/$m/$f"
      done
    done
    local dup
    dup=$(cat "$d/out.txt" "$d/err.txt" | sed 's/\x1b\[[0-9;?]*[A-Za-z]//g' | grep -ao 'Running Test Class [A-Za-z0-9.]*' | sort | uniq -d | sed 's/Running Test Class //' | tr '\n' ' ')
    echo "$name-$i exit=$rc duplicated: ${dup:-none}" | tee -a runs/dup-summary.txt
  done
}

MILL_VERSION=1.1.10-jvm ./mill alpha.test.compile beta.test.compile >/dev/null 2>&1 || true
one gitbash-direct gitbash alpha.test.testForked + beta.test.testForked
one gitbash-spike gitbash spike.test alpha.test beta.test
one bat-direct bat alpha.test.testForked + beta.test.testForked
one bat-spike bat spike.test alpha.test beta.test
for c in gitbash-direct gitbash-spike bat-direct bat-spike; do
  echo "$c: $(grep -c "^$c-[0-9]* .*duplicated: [^n]" runs/dup-summary.txt) of $n runs had a class run twice" | tee -a runs/dup-summary.txt
done

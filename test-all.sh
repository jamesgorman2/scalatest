#!/usr/bin/env bash
# Runs every test suite the feature review needs, one build-tool session at a time, so that each
# stage starts with a fresh JVM. A failing stage does not stop the stages after it.
#
# Usage: ./test-all.sh [path-to-sbt]
#
# Logs go to test-all-logs/, one per stage, and test-all-logs/summary.txt lists each stage's exit code.
# The script exits 1 if any stage failed.

set -u

sbt="${1:-sbt}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
logs="$root/test-all-logs"

# A Scala Native test executable otherwise reserves heap sized to the machine's RAM up front.
export GC_MAXIMUM_HEAP_SIZE="${GC_MAXIMUM_HEAP_SIZE:-1g}"
export GC_INITIAL_HEAP_SIZE="${GC_INITIAL_HEAP_SIZE:-64m}"

mkdir -p "$logs"
: > "$logs/summary.txt"
failed=0

run_stage() {
  local name="$1"; shift
  echo "=== $name: $*"
  (cd "$root" && "$@") 2>&1 | tee "$logs/$name.log"
  local code=${PIPESTATUS[0]}
  echo "EXIT $code  $name" | tee -a "$logs/summary.txt"
  [ "$code" -eq 0 ] || failed=1
  return "$code"
}

run_stage mima "$sbt" "all scalatestCompatible/mimaReportBinaryIssues scalacticDotty/mimaReportBinaryIssues scalatestDotty/mimaReportBinaryIssues scalacticDottyJS/mimaReportBinaryIssues scalatestDottyJS/mimaReportBinaryIssues scalacticDottyNative/mimaReportBinaryIssues scalatestDottyNative/mimaReportBinaryIssues"
run_stage scalactic-jvm "$sbt" scalacticTestDotty/test
run_stage scalatest-jvm "$sbt" scalatestTestDotty/test
run_stage consolidation "$sbt" scalatestConsolidation/test
run_stage consolidation-no-mill "$sbt" scalatestConsolidation/checkNoMillDependency
run_stage mill-plugin-version "$sbt" scalatestConsolidation/checkMillPluginVersion
run_stage scalactic-js "$sbt" scalacticTestDottyJS/test
run_stage scalatest-js "$sbt" scalatestTestDottyJS/test
run_stage scalactic-native "$sbt" scalacticTestDottyNative/test
run_stage scalatest-native "$sbt" scalatestTestDottyNative/test

# The plugin's Mill build resolves the fork from the local repository.
if run_stage publish-local "$sbt" scalatestCompatible/publishLocal scalacticDotty/publishLocal scalatestDotty/publishLocal; then
  mill_launcher="./mill"
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) mill_launcher="./mill.bat" ;; esac
  run_stage mill-plugin bash -c "cd mill-plugin && $mill_launcher --no-daemon plugin.test.testForked + plugin.integration.testForked"
else
  echo "EXIT skipped  mill-plugin (publish-local failed)" | tee -a "$logs/summary.txt"
fi

echo
echo "Summary:"
cat "$logs/summary.txt"
exit "$failed"

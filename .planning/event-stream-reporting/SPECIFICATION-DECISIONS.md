# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, split by topic, each part newest first.

## Parts

- [decisions/compatibility.md](decisions/compatibility.md) — what a reporter must receive to count as receiving what 3.2.19 sends.
- [decisions/capture-and-console.md](decisions/capture-and-console.md) — what is captured from each run, how its source is shown, and how progress output shares the console with Mill.
- [decisions/test-command.md](decisions/test-command.md) — which modules, JS environments and Mill releases the test command runs on, how it runs them and with which of a module's own arguments, and when it fails.
- [decisions/run-that-does-not-finish.md](decisions/run-that-does-not-finish.md) — what counts as a run, and what the output holds when a run's process dies or the test command is interrupted.
- [decisions/runs-labels-and-selectors.md](decisions/runs-labels-and-selectors.md) — how runs, test run labels and module selectors appear in the consolidated output.
- [decisions/event-log-contract.md](decisions/event-log-contract.md) — what a test event log carries, who may read it, which release ships the Mill plugin, and the consolidation library's API.
- [decisions/outputs.md](decisions/outputs.md) — which outputs the consolidation produces, how its human-readable output is coloured, formatted, filtered and collapsed, how it shows a node that ran more than once, and what its JSON output holds.

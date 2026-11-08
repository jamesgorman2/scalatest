# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, split by topic, each part newest first.

## Parts

- [decisions/capture-and-console.md](decisions/capture-and-console.md) — what is captured from each run, how its source is shown, and how progress output shares the console with Mill.
- [decisions/test-command.md](decisions/test-command.md) — which modules the test command runs, how it runs them, and when it fails.
- [decisions/run-that-does-not-finish.md](decisions/run-that-does-not-finish.md) — what counts as a run, and what the output holds when a run's process dies or the test command is interrupted.
- [decisions/runs-labels-and-selectors.md](decisions/runs-labels-and-selectors.md) — how runs, test run labels and module selectors appear in the consolidated output.
- [decisions/channel-contract.md](decisions/channel-contract.md) — what a channel carries, who may read it, and the consolidation library's input and API.
- [decisions/reuse.md](decisions/reuse.md) — what this epic takes from structured test reporting, and its one dropped output, JUnit XML.

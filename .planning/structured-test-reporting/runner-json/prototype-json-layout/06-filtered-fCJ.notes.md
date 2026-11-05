# 06-filtered-fCJ.json: what `-fCJ` dropped

- `-fCJ` sets `filterCharacters: "C"`; 3.2.19's `FilterReporter` drops `TestSucceeded`.
- Test `t1` loses `outcome`, `endedAt` and `duration`, all supplied by `TestSucceeded`.
- `t1` loses its recorded `info`, which rides in `TestSucceeded`; its `contents` is `[]`.
- Kept: `kind`, `name`, `label`, `location`, `startedAt` and `threadName` (from `TestStarting`).
- Failed tests `t2` and `t3`, the scope, the suite and the run are unchanged (`TestFailed` is not `C`).
- `t1`'s `output` is kept: it is feature 7's reserved key, not `TestSucceeded` data.
- `summary.testsSucceededCount` stays 1: it comes from `RunCompleted`, which `C` does not drop.

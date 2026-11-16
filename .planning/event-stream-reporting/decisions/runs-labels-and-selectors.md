# Decisions: runs, labels and selectors in the output

Decisions behind `../SPECIFICATION.md` on how runs, test run labels and module selectors appear in the consolidated output, newest first.

---

## Human-readable output shows each run's own output and abnormal end on its test run label, without naming the run.

**Alternatives**

- Leaving runs to the JSON output lets a test command fail with no visible cause, as when a worker given no task dies.
- Showing only how a run ended abnormally drops the output captured against the run, often the only explanation of that end, from the human-readable output.

**Reasoning** A reader at the console needs every cause of failure and the message that explains it, but which worker ran which suites is scheduling they did not choose (`../SPECIFICATION.md` R-082). One block for each run keeps each run's stdout and stderr in order (R-043).

**Costs** The human-readable output cannot tell a reader which suites shared a process with a crash; only the JSON output can.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

---

## A test run label whose module's tests did not run is aborted, with reason *not run* where it was skipped and *could not start* where a failure stopped it.

**Alternatives**

- One reason for both leaves the reader to infer from the presence of a failure whether the module was skipped or broken.
- A status of the label's own, beside the outcome every other node uses, adds a second way to say how a node ended.

**Reasoning** The two call for different remedies: a skipped module needs a rerun, one that could not start needs a fix (`../SPECIFICATION.md` R-058, R-114). Aborted with a reason is how the test structure already shows a node that never ran.

**Costs** A label carries an outcome only where its module's tests did not run (`../SPECIFICATION.md` R-083), so a label whose runs all failed shows none.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

-
# Architecture decisions: running a module's tests

Decisions behind `../ARCHITECTURE.md` on how the test command runs each module's tests under Mill, which processes it starts them through, and how it knows the test classes each took, newest first.

## The test manifest is compiled from Mill's test queue and claim files on the JVM, and no manifest file is written.

**Alternatives**

- Each run writing the classes it is given into its own test event log records nothing for a process that dies before its runner is constructed.
- A manifest file the test command writes before any test process starts, with an entry per fork group, repeats what the queue already holds and cannot say which worker of the group took a class.

**Reasoning** On the JVM the test command lays out each fork group's queue before it starts any worker, and each claim a worker makes is a file in the `processFolder` the test command started it in. Those files are on disk whatever the worker then does, so a class no run reported is tied to the worker that claimed it, and a class no worker claimed is still in its queue. On Scala.js and Native, Mill's test task gives a module one run in one process, so the test classes the test command holds are that process's.

**Costs** The manifest rests on Mill's `@internal` queue and claim layout; on Windows two workers' `claim.log` can record one class, so a class counts as reported if any run reported it. On Scala.js and Native the test command must learn the module's test classes itself, ahead of Mill's test task, which no prototype has tried.

**Settled by** The user, on 2026-11-11, under no sub-issue of the plan.

**See**

- `../research/mill-test-worker-args.md` § 1.2, § 2.2, § 2.3.
- `../research/mill-worker-channels.md` § 3.1, § 4.
- `../research/mill-parallel-worker-capture.md` § 5, § 6.
- `../research/emitter-plugin-spike.md` § 2.
- `../research/node-native-file-channel.md` § 5.2.

---

## Every Native test binary starts through a launcher compiled with the module's toolchain, which writes the binary's stdout and stderr to files of its own.

**Alternatives**

- Leaving the binary on the stdio it inherits from Mill passes none of its output through Mill's JVM, so the output reaches the console or the Mill daemon's files, never the plugin.
- A script launcher fails wherever `CROSSCOMPILING_EMULATOR` names `qemu-user`, which cannot load a script.

**Reasoning** With a launcher in the binary's place, on Windows and Linux, nothing the binary wrote reached the console, whether written from Scala, through C `stdio`, by a child process sharing the binary's stdio, or before `abort()`, and the plugin read that output from the files, each stream in order. Mill's exit status, test results and adapter warnings matched Mill's own Native test task. A launcher compiled with the module's `nativeClang`, which starts `<emulator words> <binary> <port>` itself, did the same under `qemu-user`. Its header gives the PID and start time of a binary that dies before any run exists in it, which no test event log carries.

**Costs** The test command compiles a launcher for every Native module it runs. The compiled launcher has run only on Linux, under `--no-daemon` and for the host's architecture, never on Windows or macOS, and none has yet run with a header or with its paths compiled in.

**Settled by**

- [Does a wrapper executable deliver a Native run's stdout and stderr to the plugin and keep them off the console?](https://github.com/jamesgorman2/scalatest/issues/41).
- [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45).

**See**

- `../research/native-stdio-wrapper.md` § 2, § 3, § 4, § 5.
- `../research/js-native-channels.md` § 5.2.

---

## The plugin installs each launcher by replacing a task's value in its own evaluation of the module's test task: `jsEnvConfig`'s on Scala.js, `nativeLink`'s on Native.

**Alternatives**

- A plugin-built `TestAdapter` re-implements Mill's discovery and task loop, and puts adapter versions on the build's class path in place of the module's.
- Overriding `jsEnvConfig` or `testTask` in the module changes the module's definition, against `../SPECIFICATION.md` R-092.

**Reasoning** Both are named tasks upstream of the tasks the plugin evaluates, so the plugin can put its own value in their place while the module's definition stays unchanged (R-092). Mill's own test body then runs, producing its results and JUnit XML. Tests ran under the launcher in 112 of 112 Scala.js module runs, and on Native the exit status and JUnit XML test cases matched Mill's own task.

**Costs** The swap relies on Mill's test task using each value only as `../ARCHITECTURE.md` § External systems describes, so a Mill change there breaks it at run time, not compile time.

**Settled by**

- [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `../research/js-stdout-launcher.md` § 2.
- `../research/mill-test-without-module-trait.md` § 2.3, § 3.3, § 4, § 6.

---

## Every Scala.js VM starts through a launcher the plugin supplies, which writes the VM's stdout and stderr to files of its own.

**Alternatives**

- Reading the VM's stdout and stderr through Mill's pipes lost 64 417 bytes already handed to the operating system on Linux when the adapter's `destroyForcibly()` closed the pipe, and would have meant narrowing `../SPECIFICATION.md` R-073.
- A Node `--require` preload that sends `process.stdout` and `process.stderr` to files misses Node's fatal-error report, which bypasses `process.stderr`.
- A `.cmd` or PowerShell launcher on Windows cannot hold the VM in a job object without compiled code, so killing it would orphan the VM, as killing the C launcher without a job object did.

**Reasoning** Node writes to a file synchronously, so under `NodeJs` and `JsDom`, on Windows and Linux, it queued nothing at any exit, no byte reached a launcher's file after the VM's exit, and every byte the VM had handed to the operating system was read. This held whether the VM exited, died or was killed, and whether the reader kept up or paused. The launcher's header gives every VM's PID, which `JsDom` window code cannot learn. On Windows and Linux it also gives the VM's start time, which matched `ProcessHandle`'s for 112 of 112 VMs.

**Costs** The plugin ships a Windows launcher binary, which Windows on ARM64 runs under emulation, and it adds about 12 ms to each VM's start-up on Windows. The launcher has not run on macOS.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `../research/js-stdout-launcher.md` § 2.3, § 3, § 5, § 7.
- `../research/jsdom-channel.md` § 4.

---

## The plugin runs a module's test discovery itself, without the runner argument, so Mill's discovery runner opens no test event log.

**Alternatives**

- Telling the discovery runner's file from a run's by comparing its PID with Mill's own fails with `javaHome` set, where discovery runs in a JVM-worker subprocess, not Mill's own process.
- Telling it by its working directory depends on `testSandboxWorkingDir`, which the module may turn off.
- Telling it by its missing `done` also matches a run whose process dies.
- Any such test still leaves the discovery runner's file, one per module per command; on Windows under the daemon, written through `FileOutputStream`, it stays open after the command and cannot be deleted.
- Mill's own JVM test task, with a logger compiled in package `mill`, keeps the runner's progress off the console, but passes discovery the runner argument.

**Reasoning** `../SPECIFICATION.md` R-072 keeps the discovery runner out of the consolidated output. With the argument withheld from discovery, no discovery file appeared, while the workers received it and ran the same classes with the same results.

**Costs** On the JVM the plugin must drive Mill's test queue itself, since evaluating the module's own test task passes discovery the same arguments as the workers. That route needs reflection on Mill's protected discovery member, `@experimental` `TestModuleUtil`, `@internal` `internalWorker`, `TestResult` and `TestRunnerUtils.globFilter`, `mill.javalib.api.internal.ZincOp` and a copy of Mill's test-queue scheduling, any of which a Mill release can break at run time, not compile time.

**Settled by** [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).

**See**

- `../research/jvm-file-channel.md` § 6.
- `../research/mill-runner-progress-without-trait.md` § Short answer, § 3, § 5, § 7.
- `../research/mill-test-worker-args.md` § 2.3.

---

## The test command runs every selected module's tests in one nested evaluation of its own with fail-fast off, rather than relying on Mill's `--keep-going`.

**Alternatives**

- Re-running the goals that fail-fast skipped, through public API only, starts their tests late and drops the console summary between rounds.
- Falling back to such rounds when reflection fails adds a code path that runs only after a Mill change has likely broken other internals too.
- Requiring `--keep-going` on the command line adds a flag to every run, and without it scheduling decides which modules report (`../SPECIFICATION.md` R-056).
- Setting `--keep-going` in the build's `mill-opts` turns fail-fast off for every Mill command in the project.

**Reasoning** The nested evaluation is one `execute` call on a copy of `mill.exec.Execution`, made by reflection with `failFast` off, which holds `../SPECIFICATION.md` R-056 without `--keep-going`. R-044, R-089 and R-092 already rest on Mill internals that a Mill change can break at run time (`capture-and-console.md` "`../SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime…" and "On the console, progress output is a one-line summary…"; `test-command.md` "A test module needs no change to its definition…"), and the JVM routes that met R-044 were observed only on the copy. Holding R-056 through the copy adds one more such dependency, not a new kind of risk.

**Costs** The route reaches a `private val` of Mill's evaluator by reflection and copies `mill.exec.Execution`, both outside Mill's compatibility policy, so a Mill release that changes either breaks it at run time, not compile time. The copy may briefly draw its own failure count on Mill's prompt before the test command ends.

**Settled by** [Does the test command hold R-056 through Mill internals, or does R-056 accept a later start for modules fail-fast skipped?](https://github.com/jamesgorman2/scalatest/issues/44).

**See**

- `../research/mill-nested-keep-going.md` § 3, § 4.1, § 4.4, § 5.
- `../research/mill-runner-progress-without-trait.md` § 1, § 9.
- `../research/nested-prompt-console.md` § 4.

---

# A launcher that captures a Native run's stdout and stderr

What was observed when plugin code built Scala Native's `TestAdapter` itself and gave it a generated launcher in place of the test binary, the launcher starting the real binary with stdout and stderr redirected to files the plugin reads. Answers [Does a wrapper executable deliver a Native run's stdout and stderr to the plugin and keep them off the console?](https://github.com/jamesgorman2/scalatest/issues/41). Observations only; no launcher form is chosen.

`.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`, holds the source reading this note tests (§ 1.2, § 5.2, § 5.3, § 6.2).

## Short answer

- **Off the console.** With the launcher, nothing the binary wrote to stdout or stderr reached the console, under the daemon and under `--no-daemon`, on Windows and Linux. This covers Scala writes, C `stdio` writes, child processes that inherit the binary's stdio, and what a binary wrote before `abort()` (§ 3).
- **To the plugin.** The plugin read all of it from the launcher's files after `TestAdapter.close()`, each stream in the order written (§ 3).
- **Exit status and com channel.** Mill's exit status, the test results and the adapter's warnings matched Mill's own Native test task in every case. Only the exit value the adapter logs for a crashed binary differed, with a Windows `.cmd` launcher (§ 4).
- **Emulator.** A script launcher works where `CROSSCOMPILING_EMULATOR` names a program that only runs its arguments. It fails under `qemu-user`, which cannot load a script. A launcher compiled to an executable, which redirects and then runs `<emulator words> <real binary> <port>` itself, worked under `qemu-user` (§ 5).
- **A crashed binary does not fail Mill.** When the test binary aborted, Mill's own Native test task and the prototype's both ended with exit status 0 (§ 4).
- **Not run:** macOS; an interactive terminal; a binary built for another architecture than the host (§ 7).

## 1. What was run

The prototype is on branch `prototype/native-stdio-wrapper`, in `.planning/event-stream-reporting/prototype-native-stdio-wrapper/`.

- **The control, `plain.test`:** Mill's `TestScalaNativeModule` unchanged.
- **The route, `wrapped.test`:** a `testTask` override that copies Mill's (`JNC` § 1.2) except that it builds `TestAdapter` itself, with `withBinaryFile(<launcher>)`. `test-runner_2.13` `0.5.12` is on the build's own class path, not loaded per module at the module's Scala Native version. After the tests it calls `adapter.close()`, waits until the capture files have not grown for 300 ms, and prints each captured line with a `PLUGIN|` prefix.
- **The launchers,** each run by the adapter as `<launcher> <port>` and each naming its files by `<port>`:
  - Linux, script: `#!/bin/sh` then `exec '<binary>' "$@" >'<dir>'/stdout-"$1".bin 2>'<dir>'/stderr-"$1".bin`. A second form runs the binary without `exec` and writes its exit status to a file.
  - Windows, script: a `.cmd` file that runs `"<binary>" %* 1>"<dir>\stdout-%1.bin" 2>"<dir>\stderr-%1.bin"`, writes `%ERRORLEVEL%` to a file, and exits with it.
  - Linux, compiled: a C program built with the module's `nativeClang`. It opens the two files, `dup2`s them onto descriptors 1 and 2, and `execvp`s `<CROSSCOMPILING_EMULATOR words> <binary> <port>`. The binary's path and the directory reach it through `TestAdapter.Config.withEnvVars`.
- **The suites,** ScalaTest `AnyFunSuite`:
  - `OutputSuite` writes 500 numbered lines to each of `System.out` and `System.err`; writes with C `fputs` to `stdout` (once flushed, once not) and `stderr`; starts two child processes with `inheritIO()`, one writing to each stream; and has one failing test.
  - `CrashSuite` writes from Scala, a child process and C, then calls `abort()`.
- **The measure.** Each Mill command ran with its own stdout and stderr redirected to files. A marker line in those files without the `PLUGIN|` prefix reached the console raw.

Versions: Mill `1.1.10`, Scala Native `0.5.12`, Scala `3.3.6`, ScalaTest `3.2.19`.

| Platform | Toolchain |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | LLVM `20.1.8` with the MSVC linker |
| Ubuntu 26.04 under WSL 2, x86-64 | clang `21.1.6`; `qemu-user` `11.0.3` |

## 2. The control

- Under `--no-daemon`, every marker of `plain.test` reached the console raw on both platforms: 1007 lines from `OutputSuite`, 4 from `CrashSuite`.
- Under the daemon, none reached the redirected console on either platform. On Windows the markers were in `out/mill-daemon/stdout` and `out/mill-daemon/stderr`. `JNC` § 5.3 infers from the `TailManager` source that they reach the console; that was not observed with the command's output redirected to files.

## 3. Capture with the launcher

The same on Windows and Linux, under the daemon and under `--no-daemon`, for each launcher form:

- No marker reached the console raw.
- `OutputSuite`: the plugin read 504 stdout lines and 503 stderr lines. The 500 numbered lines of each stream were in order. The child processes' lines sat where they were written. The unflushed C line came last on stdout, after later Scala writes: C `stdio` held it until the process exited.
- `CrashSuite`: the plugin read the Scala, child-process and C `stderr` lines written before `abort()`. The unflushed C `stdout` line was lost, as it was from the control's console.
- The files stopped growing within the 300 ms wait after `close()` in every run.
- No test binary was left running after any run.

## 4. Exit status and the com channel

| Case | Mill exit status, control and launcher | Adapter log, control | Adapter log, launcher |
|---|---|---|---|
| `OutputSuite`, one failing test | 1; "1 tests failed" | none | none |
| `CrashSuite`, Linux | 0 | "interrupted by fatal signal 6"; process "finished with non-zero value 134" | the same, naming the launcher; with a compiled launcher under `qemu-user` also |
| `CrashSuite`, Windows | 0 | "Force close … Connection reset" | "Force close … Connection reset"; launcher "finished with non-zero value 1" |

- After a crash Mill prints "Test result: 1 completed, 1 failures" and ends with exit status 0, with and without the launcher.
- The Linux script launcher that does not `exec` recorded the binary's exit status as 134 after `abort()` and 0 otherwise.
- The Windows `.cmd` launcher recorded 0 after `OutputSuite` and wrote nothing after `CrashSuite`. *Inference:* the adapter's `close()`, which calls `destroyForcibly()` on the launcher (`[sn] SNTR/ProcessRunner.scala`, `close`), ended `cmd.exe` before it wrote the file.

## 5. `CROSSCOMPILING_EMULATOR`

`ProcessRunner` runs `<emulator words> <launcher> <port>` (`JNC` § 1.2), so the emulator is given the launcher, not the binary.

| Emulator | Launcher | Result |
|---|---|---|
| `cmd /c` (Windows), `/usr/bin/env` (Linux): run their arguments | script | as § 3, under the daemon and `--no-daemon` |
| `qemu-x86_64` | none (control) | tests run; output reaches the console raw |
| `qemu-x86_64` | script | the run fails: "Error while loading …/launcher.sh: Exec format error" |
| `qemu-x86_64` | compiled | as § 3, `OutputSuite` and `CrashSuite`, under `--no-daemon` |

The compiled launcher was built for the host. `qemu-user` ran it, and it started `qemu-x86_64 <binary> <port>` as a host process.

## 6. What the prototype did not do

- It read the files after `close()`; it did not read them while the run wrote them.
- It passed the launcher its paths in the launcher's text or through the environment; it did not vary them per module or per command.
- It ran one process per task: no run started the launcher from more than one thread.

## 7. What was not determined

- Anything on macOS.
- Whether the control's output reaches an interactive terminal under the daemon (§ 2).
- Whether a compiled launcher built for another architecture than the host works under that architecture's emulator.
- Whether a compiled launcher on Windows records a crashed binary's exit status where the `.cmd` launcher does not (§ 4).
- Whether a `TestAdapter` loaded at the module's Scala Native version, apart from the build's class path, behaves the same (`JNC` § 7).
- Whether any of this holds on other versions than § 1 lists.

package org.scalatest.mill

import java.time.Instant
import mill.api.Discover
import mill.api.Evaluator
import mill.api.ExternalModule
import mill.api.Task
import mill.util.TokenReaders.millEvaluatorTokenReader

/** Nothing in a build refers to this module: `mill scalatest/test` reaches it. */
object TestCommandModule extends ExternalModule {

  /**
   * Runs the tests of every JVM test module the module selectors resolve to, each once, and writes their human-readable
   * output to stdout and, where `-fJ` is given, their JSON output to a file.
   *
   * @param arguments one or more module selectors and, in any order among them, optionally `-oH` or, to write nothing
   *   to stdout, a bare `-o`, and optionally `-fJ` and the JSON output file's path; a relative path resolves against
   *   the workspace root. Optionally `--` follows, then the arguments `mill <module>.testOnly` takes: class globs and,
   *   after a second `--`, framework arguments, as in `core.test util.test -fJ report.json -- samples.Alpha* -- -n
   *   samples.Fast`. The class globs and framework arguments apply alike to every selected module. A framework
   *   argument is ScalaTest's `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W` with its
   *   values. The command fails on any other framework argument, and on any of these flags given before the first `--`,
   *   or anywhere if no `--` is given.
   */
  def test(evaluator: Evaluator, arguments: mainargs.Leftover[String]): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      TestCommand.run(evaluator, arguments.value, Task.ctx(), () => Instant.now(), sys.props.get)
    }

  lazy val millDiscover: Discover = Discover[this.type]
}

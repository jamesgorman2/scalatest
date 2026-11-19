package org.scalatest.mill

import mill.api.Discover
import mill.api.Evaluator
import mill.api.ExternalModule
import mill.api.Task
import mill.util.TokenReaders.millEvaluatorTokenReader

/** Nothing in a build refers to this module: `mill scalatest/test` reaches it. */
object TestCommandModule extends ExternalModule {

  /**
   * Runs one JVM test module's tests and writes their human-readable output to stdout and, where `-fJ` is given, their
   * JSON output to a file.
   *
   * @param arguments the module selector; optionally `-oH`, or a bare `-o` for no output on stdout; and optionally
   *   `-fJ` followed by the JSON output file's path, which, if relative, resolves against the workspace root
   */
  def test(evaluator: Evaluator, arguments: mainargs.Leftover[String]): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      TestCommand.run(evaluator, arguments.value, Task.ctx())
    }

  lazy val millDiscover: Discover = Discover[this.type]
}

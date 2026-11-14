package org.scalatest.mill

import mill.api.Discover
import mill.api.Evaluator
import mill.api.ExternalModule
import mill.api.Task
import mill.util.TokenReaders.millEvaluatorTokenReader

/** The module holding the test command. Nothing in a build refers to it: `mill scalatest/test` reaches it. */
object TestCommandModule extends ExternalModule {

  /**
   * Runs one JVM test module's tests and writes their human-readable output to stdout.
   *
   * @param arguments the module selector
   */
  def test(evaluator: Evaluator, arguments: mainargs.Leftover[String]): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      TestCommand.run(evaluator, arguments.value, Task.ctx())
    }

  lazy val millDiscover: Discover = Discover[this.type]
}

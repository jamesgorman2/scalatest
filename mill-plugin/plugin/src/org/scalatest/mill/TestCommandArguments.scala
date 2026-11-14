package org.scalatest.mill

/**
 * The test command's arguments.
 *
 * @param moduleSelectors each argument that does not begin with `-`
 * @param humanReadableOnStdout whether stdout holds human-readable output: true unless the output argument is a bare `-o`
 */
private[mill] final case class TestCommandArguments(moduleSelectors: Seq[String], humanReadableOnStdout: Boolean)

private[mill] object TestCommandArguments {

  private val outputArguments: Map[String, Boolean] = Map("-oH" -> true, "-o" -> false)

  /** Reads the test command's arguments, failing on any that begins with `-` other than one `-oH` or bare `-o`. */
  def parse(arguments: Seq[String]): Either[String, TestCommandArguments] = {
    val (options, moduleSelectors) = arguments.partition(_.startsWith("-"))
    options match {
      case Seq() => Right(TestCommandArguments(moduleSelectors, humanReadableOnStdout = true))
      case Seq(option) if outputArguments.contains(option) => Right(TestCommandArguments(moduleSelectors, outputArguments(option)))
      case _ =>
        val rejected = options.find(!outputArguments.contains(_)).getOrElse(options(1))
        Left(s"The test command takes one output argument, -oH or -o, but was given: $rejected")
    }
  }
}

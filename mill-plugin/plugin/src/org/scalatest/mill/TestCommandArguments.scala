package org.scalatest.mill

private[mill] final case class TestCommandArguments(moduleSelectors: Seq[String], humanReadableOnStdout: Boolean)

private[mill] object TestCommandArguments {

  private val outputArguments: Map[String, Boolean] = Map("-oH" -> true, "-o" -> false)

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

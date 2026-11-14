package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/** Which of a module's own ScalaTest arguments a run receives. */
private[mill] object ModuleScalaTestArguments {

  /** The flags a run receives, with their values. Every other flag, including every reporter's, is withheld. */
  val allowedFlags: Set[String] = Set("-D", "-n", "-l", "-P", "-m", "-w", "-F", "-T", "-t", "-z", "-S", "-W")

  /**
   * The allowed arguments, with their values, in the order given; or a failure naming the first argument whose flag
   * ScalaTest's runner does not recognise.
   */
  def forRun(moduleArguments: Seq[String]): Either[String, Seq[String]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    split.collectFirst { case ScalaTestArgument.Unrecognised(argument) => argument } match {
      case Some(argument) => Left(s"The module's ScalaTest arguments hold $argument, which ScalaTest does not recognise")
      case None =>
        Right(split.flatMap {
          case ScalaTestArgument.Flagged(flag, arguments) if allowedFlags(flag) => arguments
          case _ => Nil
        })
    }
  }
}

package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  /** Every other flag, including every reporter's, is withheld with its values. */
  val allowedFlags: Set[String] = Set("-D", "-n", "-l", "-P", "-m", "-w", "-F", "-T", "-t", "-z", "-S", "-W")

  /** Fails on an argument whose flag ScalaTest's runner does not recognise, rather than withholding it. */
  def forRun(moduleArguments: Seq[String]): Either[TestCommandFailure, Seq[String]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    split.collectFirst { case ScalaTestArgument.Unrecognised(argument) => argument } match {
      case Some(argument) => Left(TestCommandFailure.UnrecognisedModuleArgument(argument))
      case None =>
        Right(split.flatMap {
          case ScalaTestArgument.Flagged(flag, arguments) if allowedFlags(flag) => arguments
          case _ => Nil
        })
    }
  }
}

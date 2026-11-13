package org.scalatest.consolidation

import org.scalatest.tools.ArgumentSplitter
import org.scalatest.tools.SplitArgument

/** One of a module's ScalaTest arguments, as ScalaTest's JVM runner reads it. */
enum ScalaTestArgument {

  /**
   * An argument whose flag the runner recognises, with its values.
   *
   * @param flag the flag without what is written onto it: `-o` for `-oD`
   * @param arguments the argument as written, then its values
   */
  case Flagged(flag: String, arguments: Seq[String])

  /** An argument whose flag the runner does not recognise. */
  case Unrecognised(argument: String)
}

object ScalaTestArgument {

  /**
   * Splits a module's ScalaTest arguments by flag, in the order given. Each flag takes the values the runner reads
   * for it, so a value is never returned as a flag. A flag short of values keeps those it has. Never throws.
   */
  def split(arguments: Seq[String]): Seq[ScalaTestArgument] =
    ArgumentSplitter.split(arguments).map {
      case SplitArgument.Flagged(flag, flagArguments) => Flagged(flag, flagArguments)
      case SplitArgument.Unrecognised(argument) => Unrecognised(argument)
    }
}

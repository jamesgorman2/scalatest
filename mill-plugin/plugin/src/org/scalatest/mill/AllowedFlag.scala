package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/** A ScalaTest flag that the test command keeps from a module's `testArgsDefault` and accepts in a framework argument. */
private[mill] enum AllowedFlag(val text: String) {
  case ConfigMapEntry extends AllowedFlag("-D")
  case IncludedTags extends AllowedFlag("-n")
  case ExcludedTags extends AllowedFlag("-l")
  case Parallel extends AllowedFlag("-P")
  case MembersOnly extends AllowedFlag("-m")
  case Wildcard extends AllowedFlag("-w")
  case SpanScaleFactor extends AllowedFlag("-F")
  case TestSortingTimeout extends AllowedFlag("-T")
  case TestName extends AllowedFlag("-t")
  case TestNameSubstring extends AllowedFlag("-z")
  case Seed extends AllowedFlag("-S")
  case SlowpokeDetection extends AllowedFlag("-W")

  /**
   * A framework argument with this flag replaces each module argument that has this flag, because ScalaTest's runner
   * rejects a second `-F`, `-T` or `-S`, keeps the first `-W`, and keeps the first `-P` holding a thread count.
   */
  def frameworkArgumentReplacesModuleArguments: Boolean =
    this match {
      case Parallel | SpanScaleFactor | TestSortingTimeout | Seed | SlowpokeDetection => true
      case _ => false
    }

  def acceptsValuesOf(flagged: ScalaTestArgument.Flagged): Boolean =
    flagged.joinedValue.exists(acceptsJoinedValue) && flagged.values.forall(acceptsSeparateValue)

  private def acceptsJoinedValue(joinedValue: String): Boolean =
    this match {
      case ConfigMapEntry => AllowedFlag.isConfigMapEntry(joinedValue)
      // ScalaTest's sbt Framework, which runs the tests under Mill, rejects a -P without a thread count.
      case Parallel => AllowedFlag.isThreadCount(joinedValue)
      case _ => joinedValue.isEmpty
    }

  private def acceptsSeparateValue(separateValue: String): Boolean =
    this match {
      case SpanScaleFactor | TestSortingTimeout => separateValue.toDoubleOption.isDefined
      case Seed => separateValue.toLongOption.isDefined
      // ScalaTest's runner rejects a -W value that is not an Int before it reads the value as a Long.
      case SlowpokeDetection => separateValue.toIntOption.isDefined
      case _ => true
    }
}

private[mill] object AllowedFlag {

  def from(argument: ScalaTestArgument.Flagged): Option[AllowedFlag] = values.find(_.text == argument.flag)

  def isHeldBy(argument: String): Boolean =
    ScalaTestArgument.split(Seq(argument)) match {
      case Seq(flagged: ScalaTestArgument.Flagged) => from(flagged).isDefined
      case _ => false
    }

  private def isConfigMapEntry(entry: String): Boolean = {
    // ScalaTest's runner rejects a `-D` entry without a key, `=` and a value.
    val equalsAt = entry.indexOf('=')
    equalsAt > 0 && equalsAt < entry.length - 1
  }

  private def isThreadCount(count: String): Boolean = count.matches("[0-9]+") && count.toIntOption.exists(_ > 0)
}

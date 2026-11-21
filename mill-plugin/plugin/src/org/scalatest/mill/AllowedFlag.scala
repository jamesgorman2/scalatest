package org.scalatest.mill

/** A ScalaTest flag that a module's own arguments keep and that a framework argument may hold. */
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

  /** Whether ScalaTest's runner accepts what is written onto the flag, such as `colour=blue` in `-Dcolour=blue`. */
  def acceptsJoinedValue(joinedValue: String): Boolean =
    this match {
      case ConfigMapEntry => AllowedFlag.isConfigMapEntry(joinedValue)
      case Parallel => joinedValue.isEmpty || AllowedFlag.isThreadCount(joinedValue)
      case _ => joinedValue.isEmpty
    }

  /** Whether ScalaTest's runner accepts a value that follows the flag, such as `60` after `-W`. */
  def acceptsSeparateValue(separateValue: String): Boolean =
    this match {
      case SpanScaleFactor | TestSortingTimeout => separateValue.toDoubleOption.isDefined
      case Seed => separateValue.toLongOption.isDefined
      case SlowpokeDetection => separateValue.toIntOption.isDefined
      case _ => true
    }
}

private[mill] object AllowedFlag {

  def from(flag: String): Option[AllowedFlag] = values.find(_.text == flag)

  private def isConfigMapEntry(entry: String): Boolean = {
    // ScalaTest's runner rejects a `-D` entry without a key, `=` and a value.
    val equalsAt = entry.indexOf('=')
    equalsAt > 0 && equalsAt < entry.length - 1
  }

  private def isThreadCount(count: String): Boolean = count.matches("[0-9]+") && count.toIntOption.exists(_ > 0)
}

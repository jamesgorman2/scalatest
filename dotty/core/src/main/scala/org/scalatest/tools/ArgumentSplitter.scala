package org.scalatest.tools

import scala.annotation.tailrec

private[scalatest] enum SplitArgument {

  case Flagged(flag: String, argumentAndValues: List[String])

  case Unrecognised(argument: String)
}

private[scalatest] object ArgumentSplitter {

  private enum Values {

    case Following(count: Int, endsAtFlag: Boolean = false)

    case DirectoryThenCssFile

    def isShortOf(heldValues: List[String]): Boolean = heldValues.size < countNeeded(heldValues)

    def splitFrom(remaining: List[String]): (List[String], List[String]) = {
      val values = remaining.take(countNeeded(remaining)).takeWhile(isValue)
      (values, remaining.drop(values.size))
    }

    private def countNeeded(arguments: List[String]): Int =
      this match {
        case Following(count, _) => count
        case DirectoryThenCssFile =>
          if (arguments.drop(1).headOption.contains(cssFileFlag))
            3
          else
            1
      }

    private def isValue(argument: String): Boolean =
      this match {
        case Following(_, endsAtFlag) => !(endsAtFlag && looksLikeFlag(argument))
        case DirectoryThenCssFile => true
      }

    private def looksLikeFlag(argument: String): Boolean = Option(argument).exists(_.startsWith("-"))
  }

  import Values.*

  // Both tables give each flag the values the JVM `ArgsParser.parseArgs` reads for it, so that a value is never read as
  // a flag.
  private val flagsMatchedByPrefix: Map[String, Values] = Map(
    "-D" -> Following(0),
    "-R" -> Following(1),
    "-g" -> Following(0),
    "-o" -> Following(0),
    "-e" -> Following(0),
    "-f" -> Following(1),
    "-h" -> DirectoryThenCssFile,
    "-C" -> Following(1),
    "-P" -> Following(0)
  )

  private val flagsMatchedExactly: Map[String, Values] = Map(
    "-M" -> Following(1),
    "-u" -> Following(1),
    "-n" -> Following(1),
    "-l" -> Following(1),
    "-s" -> Following(1),
    "-A" -> Following(1),
    "-i" -> Following(1),
    "-t" -> Following(1),
    "-z" -> Following(1),
    "-j" -> Following(1),
    "-m" -> Following(1),
    "-w" -> Following(1),
    "-b" -> Following(1),
    "-q" -> Following(1),
    "-Q" -> Following(0),
    "-k" -> Following(2, endsAtFlag = true),
    "-K" -> Following(2, endsAtFlag = true),
    "-y" -> Following(1),
    "-F" -> Following(1),
    "-S" -> Following(1),
    "-T" -> Following(1),
    // parseArgs rejects a -W whose two values are not both integers; the splitter takes whatever follows.
    "-W" -> Following(2),
    TestEventLogArgument.flag -> Following(1)
  )

  private val cssFileFlag = "-Y"

  def split(arguments: Seq[String]): List[SplitArgument] = splitAll(arguments.toList, Nil)

  @tailrec
  private def splitAll(remaining: List[String], splitSoFar: List[SplitArgument]): List[SplitArgument] =
    remaining match {
      case Nil => splitSoFar.reverse
      case argument :: following =>
        flagOf(argument) match {
          case Some(flag) => {
            val (values, afterValues) = valuesOf(flag).splitFrom(following)
            splitAll(afterValues, SplitArgument.Flagged(flag, argument :: values) :: splitSoFar)
          }
          case None => splitAll(following, SplitArgument.Unrecognised(argument) :: splitSoFar)
        }
    }

  /**
   * Whether the flag holds fewer values than the runner needs with it, such as `-n` with no tag or `-W` with one
   * integer. A flag the runner does not recognise is never short of values.
   */
  def isShortOfValues(flagged: SplitArgument.Flagged): Boolean = {
    val heldValues = flagged.argumentAndValues.drop(1)
    flagOf(flagged.flag).map(valuesOf).exists(_.isShortOf(heldValues))
  }

  private def flagOf(argument: String): Option[String] =
    Option(argument).flatMap { written =>
      Some(written.take(2)).filter(flagsMatchedByPrefix.contains).orElse(Some(written).filter(flagsMatchedExactly.contains))
    }

  private def valuesOf(flag: String): Values = flagsMatchedByPrefix.getOrElse(flag, flagsMatchedExactly(flag))
}

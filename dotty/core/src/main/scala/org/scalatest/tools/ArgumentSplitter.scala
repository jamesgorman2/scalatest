package org.scalatest.tools

import scala.collection.mutable.ListBuffer

private[scalatest] enum SplitArgument {

  case Flagged(flag: String, argumentAndValues: List[String])

  case Unrecognised(argument: String)
}

private[scalatest] object ArgumentSplitter {

  private enum Values {

    /** `count` values. Where `endsAtFlag`, an argument starting with `-` ends them early. */
    case Following(count: Int, endsAtFlag: Boolean = false)

    /** A directory, then `-Y` and a CSS file where `-Y` follows the directory. */
    case DirectoryThenCssFile

    def isShortOf(heldValues: List[String]): Boolean = heldValues.size < countNeededHolding(heldValues)

    def take(remaining: BufferedIterator[String]): List[String] = {
      val taken = new ListBuffer[String]
      while (remaining.hasNext && takesNext(remaining.head, taken.toList)) {
        taken += remaining.next()
      }
      taken.toList
    }

    private def takesNext(argument: String, heldValues: List[String]): Boolean =
      isValue(argument) && heldValues.size < countNeededHolding(heldValues :+ argument)

    private def countNeededHolding(values: List[String]): Int =
      this match {
        case Following(count, _) => count
        case DirectoryThenCssFile => if (values.lift(1).contains(cssFileFlag)) 3 else 1
      }

    private def isValue(argument: String): Boolean =
      this match {
        case Following(_, true) => argument == null || !argument.startsWith("-")
        case _ => true
      }
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

  def split(arguments: Seq[String]): List[SplitArgument] = {
    val remaining = arguments.iterator.buffered
    val split = new ListBuffer[SplitArgument]
    while (remaining.hasNext) {
      val argument = remaining.next()
      split += (flagOf(argument) match {
        case Some(flag) => SplitArgument.Flagged(flag, argument :: valuesOf(flag).take(remaining))
        case None => SplitArgument.Unrecognised(argument)
      })
    }
    split.toList
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

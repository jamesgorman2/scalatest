package org.scalatest.tools

import scala.collection.mutable.ListBuffer

private[scalatest] enum SplitArgument {

  /**
   * An argument whose flag `ArgsParser.parseArgs` recognises, with its values.
   *
   * @param flag the flag without what is written onto it: `-o` for `-oD`
   * @param arguments the argument as written, then its values
   */
  case Flagged(flag: String, arguments: List[String])

  /** An argument whose flag `ArgsParser.parseArgs` does not recognise. */
  case Unrecognised(argument: String)
}

/**
 * Groups ScalaTest arguments by flag, giving each flag the values the JVM `ArgsParser.parseArgs` reads for it, so
 * that a value is never read as a flag.
 */
private[scalatest] object ArgumentSplitter {

  private enum Values {
    case Following(count: Int)
    case FollowingUnlessFlags(count: Int)
    case DirectoryThenCssFile
  }

  import Values.*

  private val flagsWrittenOnto: Map[String, Values] = Map(
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

  private val flagsWrittenAlone: Map[String, Values] = Map(
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
    "-k" -> FollowingUnlessFlags(2),
    "-K" -> FollowingUnlessFlags(2),
    "-y" -> Following(1),
    "-F" -> Following(1),
    "-S" -> Following(1),
    "-T" -> Following(1),
    // parseArgs rejects a -W whose two values are not both integers; the splitter takes whatever follows.
    "-W" -> Following(2),
    TestEventLogArgument.flag -> Following(1)
  )

  private val cssFileFlag = "-Y"

  /** Never throws: a null argument is returned as unrecognised, and a flag short of values keeps those it has. */
  def split(arguments: Seq[String]): List[SplitArgument] = {
    val remaining = arguments.iterator.buffered
    val split = new ListBuffer[SplitArgument]
    while (remaining.hasNext) {
      val argument = remaining.next()
      split += (flagAndValuesOf(argument) match {
        case Some((flag, values)) => SplitArgument.Flagged(flag, argument :: take(values, remaining))
        case None => SplitArgument.Unrecognised(argument)
      })
    }
    split.toList
  }

  private def flagAndValuesOf(argument: String): Option[(String, Values)] =
    Option(argument).flatMap { written =>
      val flagWrittenOnto = written.take(2)
      flagsWrittenOnto.get(flagWrittenOnto).map(values => (flagWrittenOnto, values))
        .orElse(flagsWrittenAlone.get(written).map(values => (written, values)))
    }

  private def take(values: Values, remaining: BufferedIterator[String]): List[String] =
    values match {
      case Following(count) =>
        takeWhileNext(count, remaining)(_ => true)
      case FollowingUnlessFlags(count) =>
        takeWhileNext(count, remaining)(next => next == null || !next.startsWith("-"))
      case DirectoryThenCssFile =>
        val directory = takeWhileNext(1, remaining)(_ => true)
        val cssFile = takeWhileNext(1, remaining)(_ == cssFileFlag).flatMap(_ :: takeWhileNext(1, remaining)(_ => true))
        directory ::: cssFile
    }

  private def takeWhileNext(count: Int, remaining: BufferedIterator[String])(isValue: String => Boolean): List[String] = {
    val taken = new ListBuffer[String]
    while (taken.size < count && remaining.hasNext && isValue(remaining.head)) {
      taken += remaining.next()
    }
    taken.toList
  }
}

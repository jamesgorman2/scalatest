package org.scalatest.tools

import scala.collection.mutable.ListBuffer

private[scalatest] enum SplitArgument {

  case Flagged(flag: String, argumentAndValues: List[String])

  case Unrecognised(argument: String)
}

private[scalatest] object ArgumentSplitter {

  private enum Values {
    case Following(count: Int)
    case FollowingUnlessFlags(count: Int)
    case DirectoryThenCssFile
  }

  import Values.*

  // Both tables give each flag the values the JVM `ArgsParser.parseArgs` reads for it, so that a value is never read as
  // a flag.
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

  def split(arguments: Seq[String]): List[SplitArgument] = {
    val remaining = arguments.iterator.buffered
    val split = new ListBuffer[SplitArgument]
    while (remaining.hasNext) {
      val argument = remaining.next()
      split += (flagOf(argument) match {
        case Some(flag) => SplitArgument.Flagged(flag, argument :: take(valuesOf(flag), remaining))
        case None => SplitArgument.Unrecognised(argument)
      })
    }
    split.toList
  }

  private def flagOf(argument: String): Option[String] =
    Option(argument).flatMap { written =>
      Some(written.take(2)).filter(flagsWrittenOnto.contains).orElse(Some(written).filter(flagsWrittenAlone.contains))
    }

  private def valuesOf(flag: String): Values = flagsWrittenOnto.getOrElse(flag, flagsWrittenAlone(flag))

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

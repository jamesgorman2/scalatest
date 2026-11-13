package org.scalatest.tools

import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.SplitArgument.Flagged
import org.scalatest.tools.SplitArgument.Unrecognised
import org.scalatest.wordspec.AnyWordSpec

final class ArgumentSplitterSpec extends AnyWordSpec with Matchers {

  private val looksLikeAFlag = "-Dlooks=like-a-flag"
  private val alsoLooksLikeAFlag = "-Dalso=looks-like-a-flag"

  private final case class FlagWithValues(
    flag: String,
    argument: String,
    values: List[String],
    readByParseArgs: ParsedArgs => List[String]
  ) {
    val arguments: List[String] = argument :: values
    val description: String = arguments.mkString(" ")
  }

  private val flagsParseArgsKeepsWithTheirValues = List(
    FlagWithValues("-D", "-Dkey=value", Nil, _.props),
    FlagWithValues("-R", "-R", List(looksLikeAFlag), _.runpath),
    FlagWithValues("-g", "-gN", Nil, _.reporters),
    FlagWithValues("-o", "-oD", Nil, _.reporters),
    FlagWithValues("-e", "-eD", Nil, _.reporters),
    FlagWithValues("-f", "-fW", List(looksLikeAFlag), _.reporters),
    FlagWithValues("-M", "-M", List(looksLikeAFlag), _.reporters),
    FlagWithValues("-u", "-u", List(looksLikeAFlag), _.reporters),
    FlagWithValues("-h", "-h", List(looksLikeAFlag), _.reporters),
    FlagWithValues("-h", "-h", List(looksLikeAFlag, "-Y", alsoLooksLikeAFlag), _.reporters),
    FlagWithValues("-n", "-n", List(looksLikeAFlag), _.includes),
    FlagWithValues("-l", "-l", List(looksLikeAFlag), _.excludes),
    FlagWithValues("-C", "-C", List(looksLikeAFlag), _.reporters),
    FlagWithValues("-s", "-s", List(looksLikeAFlag), _.suites),
    FlagWithValues("-A", "-A", List(looksLikeAFlag), _.tryAgains),
    FlagWithValues("-i", "-i", List(looksLikeAFlag), _.suites),
    FlagWithValues("-t", "-t", List(looksLikeAFlag), _.suites),
    FlagWithValues("-z", "-z", List(looksLikeAFlag), _.suites),
    FlagWithValues("-j", "-j", List(looksLikeAFlag), _.junits),
    FlagWithValues("-m", "-m", List(looksLikeAFlag), _.membersOnly),
    FlagWithValues("-w", "-w", List(looksLikeAFlag), _.wildcard),
    FlagWithValues("-P", "-PS", Nil, _.concurrent),
    FlagWithValues("-b", "-b", List(looksLikeAFlag), _.testNGXMLFiles),
    FlagWithValues("-k", "-k", List("host", "8080"), _.reporters),
    FlagWithValues("-K", "-K", List("host", "8080"), _.reporters),
    FlagWithValues("-y", "-y", List(looksLikeAFlag), _.chosenStyles),
    FlagWithValues("-F", "-F", List(looksLikeAFlag), _.spanScaleFactor),
    FlagWithValues("-S", "-S", List(looksLikeAFlag), _.seeds),
    FlagWithValues("-T", "-T", List(looksLikeAFlag), _.testSortingReporterTimeout),
    FlagWithValues("-W", "-W", List("-1", "-2"), _.slowpokeParams)
  )

  private val flagsParseArgsDoesNotKeep = Set("-q", "-Q", TestEventLogArgument.flag)

  private val allSuffixes = "-Q"
  private val allSuffixesPattern = ".*(Spec|Suite)$"

  private def suffixesPattern(parsedArgs: ParsedArgs): Option[String] = parsedArgs.genSuffixesPattern.map(_.pattern)

  private def isRecognisedByParseArgs(argument: String): Boolean =
    try {
      ArgsParser.parseArgsWithTestEventLog(Array(argument))
      true
    } catch {
      case rejected: IllegalArgumentException =>
        rejected.getMessage != "Argument unrecognized by ScalaTest's Runner: " + argument
    }

  private val writtenAloneAndWrittenOnto: List[String] =
    ('!' to '~').toList.flatMap(letter => List("-" + letter, "-" + letter + "x"))

  private val candidateArguments: List[String] =
    writtenAloneAndWrittenOnto ++
      List("", "-", "--", "x", "samples.ExampleSpec", TestEventLogArgument.flag, TestEventLogArgument.flag + "x")

  "The argument splitter" when {

    "given each flag ArgsParser.parseArgs recognises, followed by another flag" should {
      flagsParseArgsKeepsWithTheirValues.foreach { flagWithValues =>
        s"keep ${flagWithValues.description} together, as parseArgs does" in {
          val arguments = flagWithValues.arguments :+ allSuffixes

          val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
          flagWithValues.readByParseArgs(parsedArgs) shouldBe flagWithValues.arguments
          suffixesPattern(parsedArgs) shouldBe Some(allSuffixesPattern)

          ArgumentSplitter.split(arguments) shouldBe List(
            Flagged(flagWithValues.flag, flagWithValues.arguments),
            Flagged(allSuffixes, List(allSuffixes))
          )
        }
      }

      "keep -q with its value, as parseArgs does" in {
        val arguments = List("-q", looksLikeAFlag, "-s", "samples.ExampleSpec")

        val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
        suffixesPattern(parsedArgs) shouldBe Some(s".*($looksLikeAFlag)$$")
        parsedArgs.props shouldBe Nil
        parsedArgs.suites shouldBe List("-s", "samples.ExampleSpec")

        ArgumentSplitter.split(arguments) shouldBe List(
          Flagged("-q", List("-q", looksLikeAFlag)),
          Flagged("-s", List("-s", "samples.ExampleSpec"))
        )
      }

      "give -Q no value, as parseArgs does" in {
        val arguments = List("-Q", looksLikeAFlag)

        val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
        suffixesPattern(parsedArgs) shouldBe Some(allSuffixesPattern)
        parsedArgs.props shouldBe List(looksLikeAFlag)

        ArgumentSplitter.split(arguments) shouldBe List(
          Flagged("-Q", List("-Q")),
          Flagged("-D", List(looksLikeAFlag))
        )
      }

      "keep the test event log argument with its file, as parseArgsWithTestEventLog does" in {
        val arguments = List("--test-event-log", looksLikeAFlag, allSuffixes)

        val (parsedArgs, eventLogFile) = ArgsParser.parseArgsWithTestEventLog(arguments.toArray)
        eventLogFile shouldBe Some(looksLikeAFlag)
        parsedArgs.props shouldBe Nil
        suffixesPattern(parsedArgs) shouldBe Some(allSuffixesPattern)

        ArgumentSplitter.split(arguments) shouldBe List(
          Flagged("--test-event-log", List("--test-event-log", looksLikeAFlag)),
          Flagged(allSuffixes, List(allSuffixes))
        )
      }
    }

    "given every printable flag, written alone and written onto" should {
      "recognise exactly those parseArgsWithTestEventLog recognises" in {
        val recognisedBySplitter = candidateArguments.filter { argument =>
          ArgumentSplitter.split(List(argument)) match {
            case List(Flagged(_, flagArguments)) =>
              flagArguments shouldBe List(argument)
              true
            case split =>
              split shouldBe List(Unrecognised(argument))
              false
          }
        }
        recognisedBySplitter shouldBe candidateArguments.filter(isRecognisedByParseArgs)
      }

      "recognise no flag but those tested with their values" in {
        val recognisedFlags = candidateArguments.flatMap { argument =>
          ArgumentSplitter.split(List(argument)).collect { case Flagged(flag, _) => flag }
        }.toSet
        recognisedFlags shouldBe flagsParseArgsKeepsWithTheirValues.map(_.flag).toSet ++ flagsParseArgsDoesNotKeep
      }
    }

    "given -k or -K followed by a flag" should {
      List("-k", "-K").foreach { socketReporter =>
        s"give $socketReporter no value, as parseArgs does" in {
          val arguments = List(socketReporter, "-s", "samples.ExampleSpec")

          val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
          parsedArgs.reporters shouldBe List(socketReporter)
          parsedArgs.suites shouldBe List("-s", "samples.ExampleSpec")

          ArgumentSplitter.split(arguments) shouldBe List(
            Flagged(socketReporter, List(socketReporter)),
            Flagged("-s", List("-s", "samples.ExampleSpec"))
          )
        }

        s"give $socketReporter its host alone, as parseArgs does" in {
          val arguments = List(socketReporter, "host", "-s", "samples.ExampleSpec")

          val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
          parsedArgs.reporters shouldBe List(socketReporter, "host")
          parsedArgs.suites shouldBe List("-s", "samples.ExampleSpec")

          ArgumentSplitter.split(arguments) shouldBe List(
            Flagged(socketReporter, List(socketReporter, "host")),
            Flagged("-s", List("-s", "samples.ExampleSpec"))
          )
        }
      }
    }

    "given -h whose directory is followed by a flag other than -Y" should {
      "give -h its directory alone, as parseArgs does" in {
        val arguments = List("-h", "target/html", "-s", "-Y")

        val parsedArgs = ArgsParser.parseArgs(arguments.toArray)
        parsedArgs.reporters shouldBe List("-h", "target/html")
        parsedArgs.suites shouldBe List("-s", "-Y")

        ArgumentSplitter.split(arguments) shouldBe List(
          Flagged("-h", List("-h", "target/html")),
          Flagged("-s", List("-s", "-Y"))
        )
      }
    }

    "given a flag short of its values" should {
      "keep a flag with no value alone, as parseArgs does" in {
        ArgsParser.parseArgs(Array("-z")).suites shouldBe List("-z")
        ArgumentSplitter.split(List("-z")) shouldBe List(Flagged("-z", List("-z")))
      }

      "keep -h, its directory and -Y with no CSS file together, as parseArgs does" in {
        val arguments = List("-h", "target/html", "-Y")
        ArgsParser.parseArgs(arguments.toArray).reporters shouldBe arguments
        ArgumentSplitter.split(arguments) shouldBe List(Flagged("-h", arguments))
      }

      "keep -W with its one value, where parseArgs throws" in {
        the[IllegalArgumentException] thrownBy ArgsParser.parseArgs(Array("-W", "1")) should have message
          "-W must be followed by two valid integers, the second specifying the period"
        ArgumentSplitter.split(List("-W", "1")) shouldBe List(Flagged("-W", List("-W", "1")))
      }

      "keep the test event log flag alone, where parseArgsWithTestEventLog throws" in {
        the[IllegalArgumentException] thrownBy ArgsParser.parseArgsWithTestEventLog(Array("--test-event-log")) should have message
          "--test-event-log must be followed by the path of the test event log file"
        ArgumentSplitter.split(List("--test-event-log")) shouldBe List(Flagged("--test-event-log", List("--test-event-log")))
      }
    }

    "given -W followed by arguments that are not integers" should {
      "keep the next two arguments with -W, where parseArgs throws" in {
        the[IllegalArgumentException] thrownBy ArgsParser.parseArgs(Array("-W", "-oD", "soon", "-Q")) should have message
          "-W must be followed by a valid integer specifying the delay, but got: -oD"
        ArgumentSplitter.split(List("-W", "-oD", "soon", "-Q")) shouldBe List(
          Flagged("-W", List("-W", "-oD", "soon")),
          Flagged("-Q", List("-Q"))
        )
      }
    }

    "given arguments whose flags parseArgs does not recognise" should {
      "return each ungrouped, in place among the flags it recognises" in {
        ArgumentSplitter.split(List("--unknown", "-oD", "samples.ExampleSpec", "-z", "-x", "-x", "-nFast")) shouldBe List(
          Unrecognised("--unknown"),
          Flagged("-o", List("-oD")),
          Unrecognised("samples.ExampleSpec"),
          Flagged("-z", List("-z", "-x")),
          Unrecognised("-x"),
          Unrecognised("-nFast")
        )
      }

      "return a null argument ungrouped" in {
        ArgumentSplitter.split(List("-oD", null, "-Q")) shouldBe List(
          Flagged("-o", List("-oD")),
          Unrecognised(null),
          Flagged("-Q", List("-Q"))
        )
      }
    }

    "given a null argument where a value is read" should {
      "keep it as the flag's value" in {
        ArgumentSplitter.split(List("-z", null, "-k", null, "8080")) shouldBe List(
          Flagged("-z", List("-z", null)),
          Flagged("-k", List("-k", null, "8080"))
        )
      }
    }

    "given no arguments" should {
      "return none" in {
        ArgumentSplitter.split(Nil) shouldBe Nil
      }
    }

    "given many arguments" should {
      "split them all" in {
        val count = 100000
        ArgumentSplitter.split(List.fill(count)("-Q")) shouldBe List.fill(count)(Flagged("-Q", List("-Q")))
      }
    }
  }
}

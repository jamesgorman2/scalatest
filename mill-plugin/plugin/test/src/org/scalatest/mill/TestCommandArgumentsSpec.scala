package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestCommandArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def parsed(arguments: String*): TestCommandArguments =
    TestCommandArguments.parse(arguments).fold(rejection => fail(s"${arguments.mkString(" ")} was rejected: ${rejection.message}"), identity)

  private val coreTest: Seq[ModuleSelectorArgument] = Seq(ModuleSelectorArgument("core.test"))

  private def expected(
      stdoutContent: StdoutContent = StdoutContent.HumanReadableOutput,
      jsonOutputPath: Option[String] = None,
      moduleSelectors: Seq[ModuleSelectorArgument] = coreTest,
      classGlobs: Seq[ClassGlob] = Nil
  ): TestCommandArguments =
    TestCommandArguments(moduleSelectors, stdoutContent, jsonOutputPath, classGlobs, frameworkArguments = Nil)

  private def rejection(arguments: String*): TestCommandFailure =
    TestCommandArguments.parse(arguments).left.getOrElse(fail(s"${arguments.mkString(" ")} was accepted"))

  private def classGlobs(arguments: String*): Seq[String] = parsed(arguments*).classGlobs.map(_.text)

  private def frameworkArguments(arguments: String*): Seq[Seq[String]] = parsed(arguments*).frameworkArguments.map(_.arguments)

  private def frameworkArgumentRejection(frameworkArguments: String*): TestCommandFailure =
    rejection(("core.test" +: "--" +: "--" +: frameworkArguments)*)

  "The test command's arguments" when {
    "given no output argument" should {
      "put human-readable output on stdout, and write no JSON output" in {
        parsed("core.test") shouldBe expected()
      }
    }

    "given -oH" should {
      "put human-readable output on stdout, wherever it is given" in {
        parsed("core.test", "-oH") shouldBe expected()
        parsed("-oH", "core.test") shouldBe expected()
      }
    }

    "given a bare -o" should {
      "put no output on stdout" in {
        parsed("core.test", "-o") shouldBe expected(stdoutContent = StdoutContent.NoOutput)
      }
    }

    "given -fJ followed by a path" should {
      "take the path as the JSON output file's path, not as a module selector, wherever the module selector stands" in {
        val withJsonOutputPath = expected(jsonOutputPath = Some("report.json"))
        parsed("core.test", "-fJ", "report.json") shouldBe withJsonOutputPath
        parsed("-fJ", "report.json", "core.test") shouldBe withJsonOutputPath
      }

      "keep the meaning of -oH or a bare -o beside it" in {
        parsed("-oH", "-fJ", "report.json", "core.test").stdoutContent shouldBe StdoutContent.HumanReadableOutput
        parsed("core.test", "-fJ", "report.json", "-o").stdoutContent shouldBe StdoutContent.NoOutput
      }
    }

    "given -fJ with nothing after it" should {
      "reject it as missing its path" in {
        rejection("core.test", "-fJ") shouldBe TestCommandFailure.JsonOutputPathMissing
      }
    }

    "given -fJ with a -- after it" should {
      "reject it as missing its path, not take the -- as its path" in {
        rejection("core.test", "-fJ", "--", "samples.Alpha1") shouldBe TestCommandFailure.JsonOutputPathMissing
      }
    }

    "given a second -fJ" should {
      "reject it, naming it" in {
        rejection("core.test", "-fJ", "report.json", "-fJ", "other.json") shouldBe TestCommandFailure.UnexpectedArgument("-fJ")
      }
    }

    "given no module selector" should {
      "return no module selectors, leaving their check to module selection" in {
        parsed("-oH") shouldBe expected(moduleSelectors = Nil)
      }
    }

    "given any other argument beginning with -" should {
      "reject it, naming it" in {
        for (argument <- Seq("-oP", "-oJ", "-oHP", "-oHd", "-oHy", "-f", "-fH", "-fP", "-fHP", "-fJH", "-fJc", "-e", "--halt-on-failure")) {
          rejection("core.test", argument, "report.json") shouldBe TestCommandFailure.UnexpectedArgument(argument)
        }
      }

      "reject it beside -oH and -fJ, naming it" in {
        rejection("core.test", "-oH", "-fJ", "report.json", "-fH", "other.txt") shouldBe TestCommandFailure.UnexpectedArgument("-fH")
      }
    }

    "given two of -oH and a bare -o" should {
      "reject the second" in {
        rejection("core.test", "-oH", "-o") shouldBe TestCommandFailure.UnexpectedArgument("-o")
      }
    }

    "given no --" should {
      "hold no class globs and no framework arguments" in {
        parsed("core.test", "-fJ", "report.json", "-o") shouldBe expected(StdoutContent.NoOutput, Some("report.json"))
      }
    }

    "given a -- with nothing after it" should {
      "hold no class globs and no framework arguments" in {
        parsed("core.test", "--") shouldBe expected()
      }
    }

    "given arguments after one --" should {
      "read them all as class globs, in the order given" in {
        classGlobs("core.test", "--", "samples.Beta1", "samples.Alpha*") shouldBe Seq("samples.Beta1", "samples.Alpha*")
      }

      "read an argument beginning with - as a class glob" in {
        classGlobs("core.test", "--", "-n", "-oD") shouldBe Seq("-n", "-oD")
      }

      "hold no framework arguments" in {
        parsed("core.test", "--", "samples.Alpha1").frameworkArguments shouldBe empty
      }

      "keep the test command's own arguments before the --" in {
        val arguments = parsed("-fJ", "report.json", "core.test", "-o", "--", "samples.Alpha1")
        (arguments.moduleSelectors, arguments.stdoutContent, arguments.jsonOutputPath) shouldBe
          ((coreTest, StdoutContent.NoOutput, Some("report.json")))
      }
    }

    "given arguments after a second --" should {
      "read those between the two as class globs and those after the second as framework arguments" in {
        val arguments = parsed("core.test", "--", "samples.Alpha*", "--", "-z", "slow")
        arguments.classGlobs.map(_.text) shouldBe Seq("samples.Alpha*")
        arguments.frameworkArguments shouldBe Seq(ScalaTestArgument.Flagged("-z", Seq("-z", "slow")))
      }

      "hold no class globs where nothing stands between the two" in {
        val arguments = parsed("core.test", "--", "--", "-n", "samples.Fast")
        arguments.classGlobs shouldBe empty
        arguments.frameworkArguments shouldBe Seq(ScalaTestArgument.Flagged("-n", Seq("-n", "samples.Fast")))
      }

      "hold no framework arguments where nothing follows the second" in {
        parsed("core.test", "--", "samples.Alpha1", "--") shouldBe expected(classGlobs = Seq(ClassGlob("samples.Alpha1")))
      }
    }

    "given framework arguments of each form the test command passes on" should {
      "split them by flag, each with its values, in the order given" in {
        val arguments = Seq(
          Seq("-Dcolour=blue"),
          Seq("-P"),
          Seq("-P4"),
          Seq("-n", "samples.Fast"),
          Seq("-l", "samples.Slow"),
          Seq("-m", "samples"),
          Seq("-w", "samples"),
          Seq("-F", "1.5"),
          Seq("-T", "2.5"),
          Seq("-t", "slow"),
          Seq("-z", "slow"),
          Seq("-S", "42"),
          Seq("-W", "60", "30")
        )
        frameworkArguments(("core.test" +: "--" +: "--" +: arguments.flatten)*) shouldBe arguments
      }

      "read a value that looks like a flag as the value of the flag before it" in {
        frameworkArguments("core.test", "--", "--", "-z", "-oD", "-l", "foo") shouldBe Seq(Seq("-z", "-oD"), Seq("-l", "foo"))
      }
    }

    "given a framework argument the test command does not pass on" should {
      "reject a ScalaTest flag outside those it passes on, naming the flag" in {
        frameworkArgumentRejection("-oD") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-oD")
        frameworkArgumentRejection("-s", "samples.Alpha1") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-s")
      }

      "reject an argument ScalaTest does not recognise, naming it" in {
        for (argument <- Seq("--unknown", "foo", "-x", "-nfoo")) {
          frameworkArgumentRejection(argument) shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn(argument)
        }
      }

      "reject a flag short of the values ScalaTest's runner takes with it, naming the flag" in {
        frameworkArgumentRejection("-n") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-n")
        frameworkArgumentRejection("-l", "samples.Slow", "-z") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-z")
        frameworkArgumentRejection("-W", "60") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-W")
      }

      "reject a flag followed by a value ScalaTest's runner does not accept, naming the flag" in {
        val flagsAndValues = Seq(
          Seq("-W", "soon", "later"),
          Seq("-W", "60", "later"),
          Seq("-W", "60", "1.5"),
          Seq("-F", "fast"),
          Seq("-T", "soon"),
          Seq("-S", "seed"),
          Seq("-S", "1.5")
        )
        for (flagAndValues <- flagsAndValues) {
          frameworkArgumentRejection(flagAndValues*) shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn(flagAndValues.head)
        }
      }

      "reject a -W value too large for an Int, which ScalaTest's runner does not accept, naming the flag" in {
        frameworkArgumentRejection("-W", "3000000000", "60") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-W")
        frameworkArgumentRejection("-W", "60", "3000000000") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-W")
      }

      "reject a -D without a config map entry joined to it, naming it" in {
        for (argument <- Seq("-D", "-Dcolour", "-D=blue", "-Dcolour=")) {
          frameworkArgumentRejection(argument) shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn(argument)
        }
      }

      "reject a -P with anything but a thread count joined to it, naming it" in {
        for (argument <- Seq("-P0", "-Pfoo", "-PS", "-P-4")) {
          frameworkArgumentRejection(argument) shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn(argument)
        }
      }

      "reject a third --, naming it" in {
        rejection("core.test", "--", "samples.Alpha1", "--", "-n", "samples.Fast", "--") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("--")
      }

      "name the first such argument" in {
        frameworkArgumentRejection("-n", "samples.Fast", "-oD", "foo") shouldBe TestCommandFailure.FrameworkArgumentNotPassedOn("-oD")
      }
    }

    "given a framework argument's flag among the test command's own arguments" should {
      "reject it alone, naming it" in {
        for (flag <- AllowedFlag.values.map(_.text)) {
          rejection("core.test", flag, "--", "samples.Alpha1") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments(flag)
        }
      }

      "reject it with its value joined to it, naming it" in {
        for (argument <- Seq("-Dcolour=blue", "-P4")) {
          rejection("core.test", argument, "--", "samples.Alpha1") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments(argument)
        }
      }

      "reject it where no -- is given, naming it" in {
        rejection("core.test", "-n", "samples.Fast") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n")
      }

      "reject it as the path after -fJ, naming it" in {
        rejection("core.test", "-fJ", "-Dcolour=blue") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments("-Dcolour=blue")
        rejection("core.test", "-fJ", "-n", "samples.Fast") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n")
      }

      "name it, not a later framework argument the test command does not pass on" in {
        rejection("-n", "samples.Fast", "core.test", "--", "--", "-oD") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n")
      }
    }
  }
}

package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestCommandArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def parsed(arguments: String*): TestCommandArguments =
    TestCommandArguments.parse(arguments).fold(rejection => fail(s"${arguments.mkString(" ")} was rejected: ${rejection.message}"), identity)

  private val coreTest: Seq[ModuleSelectorArgument] = Seq(ModuleSelectorArgument("core.test"))

  private def ownArguments(
      stdoutContent: StdoutContent = StdoutContent.HumanReadableOutput,
      jsonOutputPath: Option[String] = None,
      moduleSelectors: Seq[ModuleSelectorArgument] = coreTest
  ): TestCommandArguments =
    TestCommandArguments(moduleSelectors, stdoutContent, jsonOutputPath, classGlobs = Nil, frameworkArguments = Nil)

  private def rejection(arguments: String*): TestCommandFailure =
    TestCommandArguments.parse(arguments).left.getOrElse(fail(s"${arguments.mkString(" ")} was accepted"))

  private def classGlobs(arguments: String*): Seq[String] = parsed(arguments*).classGlobs.map(_.text)

  private def frameworkArguments(arguments: String*): Seq[Seq[String]] = parsed(arguments*).frameworkArguments.map(_.arguments)

  private def frameworkArgumentRejection(frameworkArguments: String*): TestCommandFailure =
    rejection(("core.test" +: "--" +: "--" +: frameworkArguments)*)

  "The test command's arguments" when {
    "given no output argument" should {
      "put human-readable output on stdout, and write no JSON output" in {
        parsed("core.test") shouldBe ownArguments()
      }
    }

    "given -oH" should {
      "put human-readable output on stdout, wherever it is given" in {
        parsed("core.test", "-oH") shouldBe ownArguments()
        parsed("-oH", "core.test") shouldBe ownArguments()
      }
    }

    "given a bare -o" should {
      "put no output on stdout" in {
        parsed("core.test", "-o") shouldBe ownArguments(stdoutContent = StdoutContent.NoOutput)
      }
    }

    "given -fJ followed by a path" should {
      "take the path as the JSON output file's path, not as a module selector, wherever the module selector stands" in {
        val expected = ownArguments(jsonOutputPath = Some("report.json"))
        parsed("core.test", "-fJ", "report.json") shouldBe expected
        parsed("-fJ", "report.json", "core.test") shouldBe expected
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

    "given a second -fJ" should {
      "reject it, naming it" in {
        rejection("core.test", "-fJ", "report.json", "-fJ", "other.json") shouldBe TestCommandFailure.UnexpectedArgument("-fJ")
      }
    }

    "given no module selector" should {
      "return no module selectors, leaving their check to module selection" in {
        parsed("-oH") shouldBe ownArguments(moduleSelectors = Nil)
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
        parsed("core.test", "-fJ", "report.json", "-o") shouldBe ownArguments(StdoutContent.NoOutput, Some("report.json"))
      }
    }

    "given a -- with nothing after it" should {
      "hold no class globs and no framework arguments" in {
        parsed("core.test", "--") shouldBe ownArguments()
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
        parsed("core.test", "--", "samples.Alpha1", "--") shouldBe ownArguments().copy(classGlobs = Seq(ClassGlob("samples.Alpha1")))
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
          Seq("-F", "2"),
          Seq("-T", "100"),
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

    "given, before the first --, a flag a framework argument may hold" should {
      "reject it alone, naming it" in {
        for (flag <- ModuleScalaTestArguments.allowedFlags.map(_.flag)) {
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

      "reject it ahead of a framework argument the test command does not pass on" in {
        rejection("-n", "samples.Fast", "core.test", "--", "--", "-oD") shouldBe TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n")
      }
    }
  }
}

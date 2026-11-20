package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestCommandArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def parsed(arguments: String*): TestCommandArguments =
    TestCommandArguments.parse(arguments).fold(rejection => fail(s"${arguments.mkString(" ")} was rejected: ${rejection.message}"), identity)

  private val coreTest: Seq[ModuleSelectorArgument] = Seq(ModuleSelectorArgument("core.test"))

  private def rejection(arguments: String*): TestCommandFailure =
    TestCommandArguments.parse(arguments).left.getOrElse(fail(s"${arguments.mkString(" ")} was accepted"))

  "The test command's arguments" when {
    "given no output argument" should {
      "put human-readable output on stdout, and write no JSON output" in {
        parsed("core.test") shouldBe TestCommandArguments(coreTest, StdoutContent.HumanReadableOutput, jsonOutputPath = None)
      }
    }

    "given -oH" should {
      "put human-readable output on stdout, wherever it is given" in {
        parsed("core.test", "-oH") shouldBe TestCommandArguments(coreTest, StdoutContent.HumanReadableOutput, jsonOutputPath = None)
        parsed("-oH", "core.test") shouldBe TestCommandArguments(coreTest, StdoutContent.HumanReadableOutput, jsonOutputPath = None)
      }
    }

    "given a bare -o" should {
      "put no output on stdout" in {
        parsed("core.test", "-o") shouldBe TestCommandArguments(coreTest, StdoutContent.NoOutput, jsonOutputPath = None)
      }
    }

    "given -fJ followed by a path" should {
      "take the path as the JSON output file's path, not as a module selector, wherever the module selector stands" in {
        val expected = TestCommandArguments(coreTest, StdoutContent.HumanReadableOutput, jsonOutputPath = Some("report.json"))
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
        parsed("-oH") shouldBe TestCommandArguments(Nil, StdoutContent.HumanReadableOutput, jsonOutputPath = None)
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
  }
}

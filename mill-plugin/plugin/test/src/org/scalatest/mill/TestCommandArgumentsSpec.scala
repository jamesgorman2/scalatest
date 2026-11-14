package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestCommandArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def rejection(arguments: String*): String =
    TestCommandArguments.parse(arguments).left.getOrElse(fail(s"${arguments.mkString(" ")} was accepted"))

  "The test command's arguments" when {
    "given no output argument" should {
      "put human-readable output on stdout" in {
        TestCommandArguments.parse(Seq("core.test")) shouldBe Right(TestCommandArguments(Seq("core.test"), humanReadableOnStdout = true))
      }
    }

    "given -oH" should {
      "put human-readable output on stdout, wherever it is given" in {
        TestCommandArguments.parse(Seq("core.test", "-oH")) shouldBe Right(TestCommandArguments(Seq("core.test"), humanReadableOnStdout = true))
        TestCommandArguments.parse(Seq("-oH", "core.test")) shouldBe Right(TestCommandArguments(Seq("core.test"), humanReadableOnStdout = true))
      }
    }

    "given a bare -o" should {
      "put no output on stdout" in {
        TestCommandArguments.parse(Seq("core.test", "-o")) shouldBe Right(TestCommandArguments(Seq("core.test"), humanReadableOnStdout = false))
      }
    }

    "given no module selector" should {
      "return no module selectors, leaving their check to module selection" in {
        TestCommandArguments.parse(Seq("-oH")) shouldBe Right(TestCommandArguments(Nil, humanReadableOnStdout = true))
      }
    }

    "given any other argument beginning with -" should {
      "reject it, naming it" in {
        for (argument <- Seq("-oP", "-oJ", "-oHP", "-oHd", "-oHy", "-fH", "-e", "--halt-on-failure")) {
          rejection("core.test", argument) should endWith(s": $argument")
        }
      }

      "reject it beside -oH, naming it" in {
        rejection("core.test", "-oH", "-fJ", "report.txt") should endWith(": -fJ")
      }
    }

    "given two output arguments the command takes" should {
      "reject the second" in {
        rejection("core.test", "-oH", "-o") should endWith(": -o")
      }
    }
  }
}

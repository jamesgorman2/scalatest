package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class RunArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def flagged(arguments: String*): Seq[ScalaTestArgument.Flagged] =
    ScalaTestArgument.split(arguments).collect { case argument: ScalaTestArgument.Flagged => argument }

  private def runArguments(moduleArguments: Seq[String], frameworkArguments: Seq[String]): Seq[String] =
    RunArguments.from(flagged(moduleArguments*), flagged(frameworkArguments*)).commandLine

  "A run's arguments" when {
    "the module and the test command each give arguments whose flags the runner takes more than once" should {
      "be the module's arguments, then the framework arguments, each in the order given" in {
        runArguments(
          Seq("-Dcolour=red", "-n", "samples.Slow", "-z", "adds"),
          Seq("-l", "samples.Fast", "-Dcolour=blue", "-n", "samples.Fast")
        ) shouldBe Seq("-Dcolour=red", "-n", "samples.Slow", "-z", "adds", "-l", "samples.Fast", "-Dcolour=blue", "-n", "samples.Fast")
      }
    }

    for (
      (moduleArgument, frameworkArgument) <- Seq(
        Seq("-P4") -> Seq("-P2"),
        Seq("-F", "2") -> Seq("-F", "3"),
        Seq("-T", "10") -> Seq("-T", "20"),
        Seq("-S", "42") -> Seq("-S", "7"),
        Seq("-W", "60", "30") -> Seq("-W", "120", "60")
      )
    ) {
      s"the module gives ${moduleArgument.mkString(" ")} twice and the test command gives ${frameworkArgument.mkString(" ")}" should {
        s"hold the framework argument ${frameworkArgument.mkString(" ")}, no copy of the module's ${moduleArgument.mkString(" ")}, and every other argument" in {
          runArguments(Seq("-l", "samples.Slow") ++ moduleArgument ++ moduleArgument, Seq("-z", "adds") ++ frameworkArgument) shouldBe
            Seq("-l", "samples.Slow", "-z", "adds") ++ frameworkArgument
        }
      }
    }

    "the module gives flags the runner takes only once and the test command gives none of them" should {
      "keep the module's arguments" in {
        runArguments(Seq("-F", "2", "-S", "42"), Seq("-T", "20")) shouldBe Seq("-F", "2", "-S", "42", "-T", "20")
      }
    }

    "neither gives any argument" should {
      "be empty" in {
        runArguments(Nil, Nil) shouldBe Nil
      }
    }
  }
}

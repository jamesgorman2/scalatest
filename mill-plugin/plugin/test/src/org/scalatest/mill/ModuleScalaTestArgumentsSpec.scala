package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ModuleScalaTestArgumentsSpec extends AnyWordSpec with should.Matchers {

  private def keptArguments(moduleArguments: Seq[String]): Either[Seq[String], Seq[String]] =
    ModuleScalaTestArguments.kept(moduleArguments).map(_.flatMap(_.arguments))

  "A module's own ScalaTest arguments" when {
    "every flag is one a run receives" should {
      "all reach the run, each with its values, in the order given" in {
        val arguments = Seq(
          "-Dkey=value",
          "-n", "samples.Fast",
          "-l", "samples.Slow",
          "-P4",
          "-m", "samples",
          "-w", "samples",
          "-F", "2",
          "-T", "100",
          "-t", "adds two numbers",
          "-z", "-y",
          "-S", "42",
          "-W", "60", "30"
        )
        keptArguments(arguments) shouldBe Right(arguments)
      }
    }

    "they hold reporter, JUnit, TestNG and other arguments" should {
      "withhold each with its values, keeping those a run receives" in {
        val arguments = Seq(
          "-oD",
          "-l", "samples.Slow",
          "-eD",
          "-f", "report.txt",
          "-u", "junit",
          "-j", "samples.JUnitSuite",
          "-b", "testng.xml",
          "-s", "samples.OtherSuite",
          "-R", "classes",
          "-z", "adds"
        )
        keptArguments(arguments) shouldBe Right(Seq("-l", "samples.Slow", "-z", "adds"))
      }

      "withhold a value that looks like a flag a run receives" in {
        keptArguments(Seq("-s", "-z", "-l", "samples.Slow")) shouldBe Right(Seq("-l", "samples.Slow"))
      }
    }

    "they hold arguments ScalaTest does not recognise" should {
      "keep none, giving each such argument in the order given" in {
        keptArguments(Seq("-x", "-l", "samples.Slow", "--not-a-scalatest-argument")) shouldBe
          Left(Seq("-x", "--not-a-scalatest-argument"))
      }
    }

    "they are empty" should {
      "give the run none" in {
        keptArguments(Nil) shouldBe Right(Nil)
      }
    }
  }
}

package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ModuleScalaTestArgumentsSpec extends AnyWordSpec with should.Matchers {

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
        ModuleScalaTestArguments.forRun(arguments) shouldBe Right(arguments)
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
        ModuleScalaTestArguments.forRun(arguments) shouldBe Right(Seq("-l", "samples.Slow", "-z", "adds"))
      }

      "withhold a value that looks like a flag a run receives" in {
        ModuleScalaTestArguments.forRun(Seq("-s", "-z", "-l", "samples.Slow")) shouldBe Right(Seq("-l", "samples.Slow"))
      }
    }

    "they hold an argument whose flag ScalaTest does not recognise" should {
      "fail, naming the first such argument" in {
        val failure = ModuleScalaTestArguments.forRun(Seq("-l", "samples.Slow", "-x", "-y"))
        failure.isLeft shouldBe true
        failure.left.getOrElse("") should include(" -x,")
      }
    }

    "they are empty" should {
      "give the run none" in {
        ModuleScalaTestArguments.forRun(Nil) shouldBe Right(Nil)
      }
    }
  }
}

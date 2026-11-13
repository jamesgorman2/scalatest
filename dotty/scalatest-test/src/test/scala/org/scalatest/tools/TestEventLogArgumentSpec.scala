package org.scalatest.tools

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestEventLogArgumentSpec extends AnyWordSpec with Matchers {

  "The test event log argument" should {
    "be its flag followed by the file's path" in {
      TestEventLogArgument.forFile("out/events.log") shouldBe List("--test-event-log", "out/events.log")
    }
  }

  "ArgsParser, parsing for a runner that writes a test event log" should {
    "return the file the argument names" in {
      val (_, eventLogFile) = ArgsParser.parseArgsWithTestEventLog(Array("--test-event-log", "out/events.log"))
      eventLogFile shouldBe Some("out/events.log")
    }

    "return no file where the argument is absent" in {
      val (_, eventLogFile) = ArgsParser.parseArgsWithTestEventLog(Array("-oD"))
      eventLogFile shouldBe None
    }

    "parse the arguments around it as it does without it" in {
      val others = Array("-s", "samples.SqrtPropSpec", "-oD", "-n", "Fast")
      val (parsedArgs, _) =
        ArgsParser.parseArgsWithTestEventLog(Array("-s", "samples.SqrtPropSpec", "--test-event-log", "out/events.log", "-oD", "-n", "Fast"))
      parsedArgs shouldBe ArgsParser.parseArgs(others)
    }

    "take a path that looks like a flag as the path" in {
      val (parsedArgs, eventLogFile) = ArgsParser.parseArgsWithTestEventLog(Array("--test-event-log", "-oD"))
      eventLogFile shouldBe Some("-oD")
      parsedArgs.reporters shouldBe Nil
    }

    "take the flag as a value where it follows a flag that takes a value" in {
      val (parsedArgs, eventLogFile) = ArgsParser.parseArgsWithTestEventLog(Array("-z", "--test-event-log"))
      eventLogFile shouldBe None
      parsedArgs.suites shouldBe List("-z", "--test-event-log")
    }

    "reject the flag with no path after it" in {
      the[IllegalArgumentException] thrownBy ArgsParser.parseArgsWithTestEventLog(Array("-oD", "--test-event-log")) should have message
        "--test-event-log must be followed by the path of the test event log file"
    }

    "reject the argument given twice" in {
      the[IllegalArgumentException] thrownBy
        ArgsParser.parseArgsWithTestEventLog(Array("--test-event-log", "a.log", "--test-event-log", "b.log")) should have message
        "Only one --test-event-log allowed"
    }
  }

  "ArgsParser, parsing for a runner that writes no test event log" should {
    "reject the argument as one it does not recognise" in {
      the[IllegalArgumentException] thrownBy ArgsParser.parseArgs(Array("--test-event-log", "out/events.log")) should have message
        "Argument unrecognized by ScalaTest's Runner: --test-event-log"
    }
  }

  "ArgsParser, checking Runner's arguments" should {
    "find the argument and its path valid" in {
      ArgsParser.checkArgsForValidity(Array("--test-event-log", "out/events.log", "-oD")) shouldBe None
    }

    "still name an argument it does not recognise" in {
      ArgsParser.checkArgsForValidity(Array("--test-event-log", "out/events.log", "--other")) shouldBe
        Some("Argument unrecognized by ScalaTest's Runner: --other.")
    }
  }
}

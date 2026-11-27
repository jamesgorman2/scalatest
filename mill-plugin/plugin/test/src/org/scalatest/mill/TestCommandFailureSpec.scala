package org.scalatest.mill

import java.io.IOException
import java.nio.file.Paths
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestCommandFailureSpec extends AnyWordSpec with should.Matchers {

  private val path = Paths.get("out", "report.json")

  "A test command failure's message" when {
    "-fJ has no path after it" should {
      "say -fJ takes a path" in {
        TestCommandFailure.JsonOutputPathMissing.message shouldBe "The test command's -fJ takes a path, but none follows it: -fJ"
      }
    }

    "an argument is unexpected" should {
      "say which arguments the command takes, naming the argument" in {
        TestCommandFailure.UnexpectedArgument("-oP").message shouldBe
          "The test command takes at most one of -oH or a bare -o, and at most one -fJ followed by a path, but was given: -oP"
      }
    }

    "a framework argument's flag is among the test command's own arguments" should {
      "say the flags are taken only after a second --, naming the argument" in {
        TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n").message shouldBe
          "The test command takes ScalaTest's -D, -n, -l, -P, -m, -w, -F, -T, -t, -z, -S and -W only as framework arguments, " +
          "after a second --, but was given among its own arguments: -n"
      }
    }

    "a framework argument is not one the test command passes on" should {
      "say which framework arguments the command takes, naming the argument" in {
        TestCommandFailure.FrameworkArgumentNotPassedOn("-oD").message shouldBe
          "The test command takes only ScalaTest's -D, -n, -l, -P, -m, -w, -F, -T, -t, -z, -S and -W as framework arguments, " +
          "each with the values ScalaTest's runner takes with it, but was given: -oD"
      }
    }

    "no module selector is given" should {
      "say the command takes one or more" in {
        TestCommandFailure.NoModuleSelector.message shouldBe "The test command takes one or more module selectors, but was given none"
      }
    }

    "a module selector resolves to no test module" should {
      "name the selector" in {
        TestCommandFailure.NoTestModule(ModuleSelectorArgument("core")).message shouldBe "Module selector core resolves to no test module"
      }
    }

    "a module selector resolves to no module" should {
      "name the selector and Mill's error" in {
        TestCommandFailure.UnresolvedModuleSelector(ModuleSelectorArgument("missing"), "Cannot resolve missing").message shouldBe
          "Module selector missing resolves to no module: Cannot resolve missing"
      }
    }

    "the test command cannot run selected test modules" should {
      "name each module, on its own line, with why the test command cannot run it" in {
        TestCommandFailure
          .CannotRunModules(
            Seq(
              CannotRunReason.NotJvm(ModuleName("js.test")),
              CannotRunReason.WithoutThisRelease(ModuleName("munit.test")),
              CannotRunReason.UnrecognisedArguments(ModuleName("args.test"), Seq("-x", "--not-a-scalatest-argument"))
            ),
            Seq.empty
          )
          .message shouldBe
          Seq(
            "The test command cannot run these selected test modules:",
            "  js.test is a Scala.js or Native test module",
            s"  munit.test does not depend on com.edc:scalatest-core_3 at ${Consolidation.scalaTestVersion}, " +
              "the release of this fork that the Mill plugin belongs to",
            "  args.test's testArgsDefault holds -x and --not-a-scalatest-argument, which ScalaTest does not recognise"
          ).mkString("\n")
      }

      "also name, after them, each module whose tests cannot start, with each line of its failure beneath it" in {
        TestCommandFailure
          .CannotRunModules(
            Seq(CannotRunReason.NotJvm(ModuleName("js.test"))),
            Seq(
              FailedModuleCheck(ModuleName("broken.test"), "Resolution failed for 1 modules:\n  com.example:absent_3:0.0.1 not found"),
              FailedModuleCheck(ModuleName("unread.test"), "unread.test.testArgsDefault testArgsDefault fails")
            )
          )
          .message shouldBe
          Seq(
            "The test command cannot run these selected test modules:",
            "  js.test is a Scala.js or Native test module",
            "The tests of these selected test modules cannot start:",
            "  broken.test:",
            "    Resolution failed for 1 modules:",
            "      com.example:absent_3:0.0.1 not found",
            "  unread.test:",
            "    unread.test.testArgsDefault testArgsDefault fails"
          ).mkString("\n")
      }
    }

    "the tests of selected modules failed" should {
      "name the one module that failed" in {
        TestCommandFailure.ModulesFailed(Seq(ModuleName("broken.test"))).message shouldBe "The tests of broken.test failed"
      }

      "name each module that failed" in {
        TestCommandFailure.ModulesFailed(Seq(ModuleName("alpha.test"), ModuleName("broken.test"), ModuleName("core.test"))).message shouldBe
          "The tests of alpha.test, broken.test and core.test failed"
      }
    }

    "the test command failed and no selected module did" should {
      "say the failure is outside the tests of every selected module" in {
        TestCommandFailure.FailedOutsideSelectedModules.message shouldBe "The test command failed outside the tests of every selected module"
      }
    }

    "the JSON output path is a folder" should {
      "name the path" in {
        TestCommandFailure.JsonOutputPathIsFolder(path).message shouldBe
          s"The test command cannot delete $path, the file -fJ names: it is a folder"
      }
    }

    "the JSON output file is not deleted" should {
      "name the path and the cause" in {
        TestCommandFailure.JsonOutputNotDeleted(path, new IOException("locked")).message shouldBe
          s"The test command cannot delete $path, the file -fJ names: java.io.IOException: locked"
      }
    }

    "the JSON output is not written" should {
      "name the path and the cause" in {
        TestCommandFailure.JsonOutputNotWritten(path, new IOException("no space left"), None).message shouldBe
          s"The test command cannot write its JSON output to $path, the file -fJ names: java.io.IOException: no space left"
      }

      "also name the failed delete of the partly written file" in {
        TestCommandFailure.JsonOutputNotWritten(path, new IOException("no space left"), Some(new IOException("locked"))).message shouldBe
          s"The test command cannot write its JSON output to $path, the file -fJ names: java.io.IOException: no space left" +
            "; it cannot delete the partly written file either: java.io.IOException: locked"
      }
    }
  }
}

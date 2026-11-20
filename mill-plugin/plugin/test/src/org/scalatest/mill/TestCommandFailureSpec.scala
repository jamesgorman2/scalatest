package org.scalatest.mill

import java.io.IOException
import java.nio.file.Paths
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

    "no module selector is given" should {
      "say the command takes one" in {
        TestCommandFailure.NoModuleSelector.message shouldBe "The test command takes one module selector, but was given none"
      }
    }

    "several module selectors are given" should {
      "say the command takes one, naming each separated by a space" in {
        TestCommandFailure.SeveralModuleSelectors(Seq(ModuleSelectorArgument("core.test"), ModuleSelectorArgument("util.test"))).message shouldBe
          "The test command takes one module selector, but was given: core.test util.test"
      }
    }

    "a module selector resolves to other than one JVM test module" should {
      "name the selector and what it resolved to" in {
        TestCommandFailure.NotOneJvmTestModule(ModuleSelectorArgument("core"), "nothing").message shouldBe
          "Module selector core must resolve to one JVM test module, but resolved to: nothing"
      }
    }

    "a module selector resolves to no module" should {
      "name the selector and Mill's error" in {
        TestCommandFailure.UnresolvedModuleSelector(ModuleSelectorArgument("missing"), "Cannot resolve missing").message shouldBe
          "Module selector missing resolves to no module: Cannot resolve missing"
      }
    }

    "the module's inputs are not evaluated" should {
      "be Mill's error" in {
        TestCommandFailure.ModuleInputsNotEvaluated("core.test", "compile failed").message shouldBe "compile failed"
      }
    }

    "the module's ScalaTest arguments hold an unrecognised argument" should {
      "name the argument" in {
        TestCommandFailure.UnrecognisedModuleArgument("-x").message shouldBe
          "The module's ScalaTest arguments hold -x, which ScalaTest does not recognise"
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

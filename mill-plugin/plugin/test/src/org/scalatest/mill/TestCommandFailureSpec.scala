package org.scalatest.mill

import java.io.IOException
import java.nio.file.Paths
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

    "a module selector resolves to Scala.js or Native test modules" should {
      "name the selector and each such module" in {
        TestCommandFailure.NotJvmTestModules(ModuleSelectorArgument("__.test"), Seq(ModuleName("coreJS.test"), ModuleName("coreNative.test"))).message shouldBe
          "Module selector __.test resolves to Scala.js or Native test modules, which the test command cannot run: coreJS.test and coreNative.test"
      }
    }

    "a module selector resolves to no module" should {
      "name the selector and Mill's error" in {
        TestCommandFailure.UnresolvedModuleSelector(ModuleSelectorArgument("missing"), "Cannot resolve missing").message shouldBe
          "Module selector missing resolves to no module: Cannot resolve missing"
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

package org.scalatest.mill

import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ModuleCheckSpec extends AnyWordSpec with should.Matchers {

  private val module = ModuleName("core.test")

  private val forkCore = ResolvedDependency("com.edc", "scalatest-core_3", Consolidation.scalaTestVersion)

  private val scalaLibrary = ResolvedDependency("org.scala-lang", "scala3-library_3", "3.3.8")

  private val frameworkArguments: Seq[ScalaTestArgument.Flagged] =
    ScalaTestArgument.split(Seq("-z", "adds")).collect { case flagged: ScalaTestArgument.Flagged => flagged }

  private def runArguments(testArgsDefault: Seq[String], runtimeDependencies: ResolvedDependency*): Either[Seq[UnrunnableModule], Seq[String]] =
    ModuleCheck(testArgsDefault, runtimeDependencies.toSet).runArguments(module, frameworkArguments).map(_.commandLine)

  "A module's check" when {
    "its runtime dependencies hold this fork's core at the version the consolidation library was built at" should {
      "give the module's kept arguments, then the framework arguments" in {
        runArguments(Seq("-l", "samples.Slow", "-oD"), scalaLibrary, forkCore) shouldBe Right(Seq("-l", "samples.Slow", "-z", "adds"))
      }
    }

    "its runtime dependencies hold this fork's core at another version" should {
      "say the module does not test with this release" in {
        runArguments(Nil, scalaLibrary, forkCore.copy(version = s"${Consolidation.scalaTestVersion}-other")) shouldBe
          Left(Seq(UnrunnableModule.WithoutThisRelease(module)))
      }
    }

    "its runtime dependencies hold upstream ScalaTest's core at the same version" should {
      "say the module does not test with this release" in {
        runArguments(Nil, scalaLibrary, forkCore.copy(organization = "org.scalatest")) shouldBe
          Left(Seq(UnrunnableModule.WithoutThisRelease(module)))
      }
    }

    "its testArgsDefault holds arguments ScalaTest does not recognise" should {
      "name each such argument" in {
        runArguments(Seq("-x", "-l", "samples.Slow", "--not-a-scalatest-argument"), forkCore) shouldBe
          Left(Seq(UnrunnableModule.UnrecognisedArguments(module, Seq("-x", "--not-a-scalatest-argument"))))
      }
    }

    "it neither tests with this release nor has only arguments ScalaTest recognises" should {
      "give both reasons" in {
        runArguments(Seq("-x"), scalaLibrary) shouldBe
          Left(Seq(UnrunnableModule.WithoutThisRelease(module), UnrunnableModule.UnrecognisedArguments(module, Seq("-x"))))
      }
    }
  }
}

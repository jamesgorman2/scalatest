package org.scalatest.mill

import org.scalatest.matchers.should.Matchers.{empty, shouldBe, withClue}

final class SandboxReset private (private val steps: Seq[SandboxReset.Step]) {

  def and(other: SandboxReset): SandboxReset = new SandboxReset(steps ++ other.steps)

  def restore(build: FixtureBuild): Unit =
    steps.foreach { step =>
      step.restore(build)
      withClue(s"after the shared sandbox's reset, ${step.restoredState}: ") {
        step.shouldBeRestored(build)
      }
    }
}

object SandboxReset {

  private final case class Step(restoredState: String, restore: FixtureBuild => Unit, shouldBeRestored: FixtureBuild => Unit)

  private def of(restoredState: String)(restore: FixtureBuild => Unit)(shouldBeRestored: FixtureBuild => Unit): SandboxReset =
    new SandboxReset(Seq(Step(restoredState, restore, shouldBeRestored)))

  val nothingBeyondTheBuildFile: SandboxReset = new SandboxReset(Nil)

  val emptyMarkersFolder: SandboxReset =
    of("the markers folder exists and is empty") { build =>
      os.remove.all(build.markersFolder)
      os.makeDir.all(build.markersFolder)
    } { build =>
      os.isDir(build.markersFolder) shouldBe true
      os.list(build.markersFolder) shouldBe empty
    }

  val noMarkersFolder: SandboxReset =
    of("nothing exists at the markers folder's path")(build => os.remove.all(build.markersFolder))(build => os.exists(build.markersFolder) shouldBe false)

  val noJsonOutputFile: SandboxReset =
    of("nothing exists at the JSON output file's path")(build => os.remove.all(build.jsonOutputFile))(build => os.exists(build.jsonOutputFile) shouldBe false)

  def nothingAt(paths: os.SubPath*): SandboxReset =
    of(s"nothing exists at ${paths.mkString(", ")} in the workspace") { build =>
      paths.foreach(path => os.remove.all(build.workspace / path))
    }(build => paths.filter(path => os.exists(build.workspace / path)) shouldBe empty)

  val noModuleFolders: SandboxReset =
    of("no module folder, test worker or test event log exists under out") { build =>
      os.remove.all(build.modulesFolder)
    } { build =>
      os.exists(build.modulesFolder) shouldBe false
      build.anyTestWorkerStarted shouldBe false
      build.eventLogFiles shouldBe empty
    }

  def fixtureFiles(paths: os.SubPath*): SandboxReset =
    of(s"${paths.mkString(", ")} in the workspace are as the fixture holds them") { build =>
      paths.filter(path => build.filesDifferingFromTheFixture(path).nonEmpty).foreach { path =>
        os.remove.all(build.workspace / path)
        if (os.exists(build.fixtureSource / path)) {
          os.copy(build.fixtureSource / path, build.workspace / path, createFolders = true)
        }
      }
    }(build => paths.flatMap(build.filesDifferingFromTheFixture) shouldBe empty)

  def buildFileHolding(buildText: String): SandboxReset =
    of("the build file holds what it held after the shared sandbox's set-up") { build =>
      if (build.buildText != buildText) {
        build.editBuild(_ => buildText)
      }
    }(build => build.buildText shouldBe buildText)

  def jsonOutputFileAsSavedIn(savedCopy: os.SubPath): SandboxReset =
    of(s"the JSON output file holds what $savedCopy in the workspace holds") { build =>
      os.copy.over(build.workspace / savedCopy, build.jsonOutputFile)
    } { build =>
      os.isFile(build.jsonOutputFile) shouldBe true
      os.read(build.jsonOutputFile) shouldBe os.read(build.workspace / savedCopy)
    }
}

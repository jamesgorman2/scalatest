package org.scalatest.mill

import org.scalatest.matchers.should.Matchers.{shouldBe, withClue}

final class SandboxReset private (private val steps: Seq[SandboxReset.Step]) {

  def and(other: SandboxReset): SandboxReset = new SandboxReset(steps ++ other.steps)

  def restore(build: FixtureBuild): Unit =
    steps.foreach { step =>
      step.restore(build)
      withClue(s"after the shared sandbox's reset, ${step.restoredState}: ") {
        step.isRestored(build) shouldBe true
      }
    }
}

object SandboxReset {

  private final case class Step(restoredState: String, restore: FixtureBuild => Unit, isRestored: FixtureBuild => Boolean)

  private def of(restoredState: String)(restore: FixtureBuild => Unit)(isRestored: FixtureBuild => Boolean): SandboxReset =
    new SandboxReset(Seq(Step(restoredState, restore, isRestored)))

  val emptyMarkersFolder: SandboxReset =
    of("the markers folder exists and is empty") { build =>
      os.remove.all(build.markersFolder)
      os.makeDir.all(build.markersFolder)
    }(build => os.isDir(build.markersFolder) && os.list(build.markersFolder).isEmpty)

  val noMarkersFolder: SandboxReset =
    of("nothing exists at the markers folder's path")(build => os.remove.all(build.markersFolder))(build => !os.exists(build.markersFolder))

  val noJsonOutputFile: SandboxReset =
    of("nothing exists at the JSON output file's path")(build => os.remove.all(build.jsonOutputFile))(build => !os.exists(build.jsonOutputFile))

  def nothingAt(paths: os.SubPath*): SandboxReset =
    of(s"nothing exists at ${paths.mkString(", ")} in the workspace") { build =>
      paths.foreach(path => os.remove.all(build.workspace / path))
    }(build => paths.forall(path => !os.exists(build.workspace / path)))

  val noModuleFolders: SandboxReset =
    of("no module folder, test worker or test event log exists under out") { build =>
      os.remove.all(build.modulesFolder)
    }(build => !os.exists(build.modulesFolder) && !build.anyTestWorkerStarted && build.eventLogFiles.isEmpty)

  def fixtureFiles(paths: os.SubPath*): SandboxReset =
    of(s"${paths.mkString(", ")} in the workspace are as the fixture holds them") { build =>
      paths.filterNot(build.isAsInTheFixture).foreach { path =>
        os.remove.all(build.workspace / path)
        if (os.exists(build.fixtureSource / path)) {
          os.copy(build.fixtureSource / path, build.workspace / path, createFolders = true)
        }
      }
    }(build => paths.forall(build.isAsInTheFixture))

  def jsonOutputFileAsSavedIn(savedCopy: os.SubPath): SandboxReset =
    of(s"the JSON output file holds what $savedCopy in the workspace holds") { build =>
      os.copy.over(build.workspace / savedCopy, build.jsonOutputFile)
    }(build => os.isFile(build.jsonOutputFile) && os.read.bytes(build.jsonOutputFile).sameElements(os.read.bytes(build.workspace / savedCopy)))
}

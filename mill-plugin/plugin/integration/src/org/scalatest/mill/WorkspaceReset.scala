package org.scalatest.mill

import org.scalatest.matchers.should.Matchers.{empty, shouldBe, withClue}

final class WorkspaceReset private (private val steps: Seq[WorkspaceReset.Step]) {

  def and(other: WorkspaceReset): WorkspaceReset = new WorkspaceReset(steps ++ other.steps)

  def restore(build: FixtureBuild): Unit =
    steps.foreach { step =>
      step.restore(build)
      withClue(s"after the shared build's reset, expected that ${step.restoredState}, but ") {
        step.shouldBeRestored(build)
      }
    }
}

object WorkspaceReset {

  private final case class Step(restoredState: String, restore: FixtureBuild => Unit, shouldBeRestored: FixtureBuild => Unit)

  private def of(restoredState: String)(restore: FixtureBuild => Unit)(shouldBeRestored: FixtureBuild => Unit): WorkspaceReset =
    new WorkspaceReset(Seq(Step(restoredState, restore, shouldBeRestored)))

  val onlyTheBuildFile: WorkspaceReset = new WorkspaceReset(Nil)

  val emptyMarkersFolder: WorkspaceReset =
    of("the markers folder exists and is empty") { build =>
      os.remove.all(build.markersFolder)
      os.makeDir.all(build.markersFolder)
    } { build =>
      os.isDir(build.markersFolder) shouldBe true
      os.list(build.markersFolder) shouldBe empty
    }

  val noMarkersFolder: WorkspaceReset =
    of("nothing exists at the markers folder's path")(build => os.remove.all(build.markersFolder))(build => os.exists(build.markersFolder) shouldBe false)

  val noJsonOutputFile: WorkspaceReset =
    of("nothing exists at the JSON output file's path")(build => os.remove.all(build.jsonOutputFile))(build => os.exists(build.jsonOutputFile) shouldBe false)

  def nothingAt(paths: os.SubPath*): WorkspaceReset =
    of(s"nothing exists at ${paths.mkString(", ")} in the workspace") { build =>
      paths.foreach(path => os.remove.all(build.workspace / path))
    }(build => paths.filter(path => os.exists(build.workspace / path)) shouldBe empty)

  val noModuleFolders: WorkspaceReset =
    of("no module folder, test worker or test event log exists under out") { build =>
      os.remove.all(build.modulesFolder)
    } { build =>
      os.exists(build.modulesFolder) shouldBe false
      build.anyTestWorkerStarted shouldBe false
      build.eventLogFiles shouldBe empty
    }

  def fixtureFiles(paths: os.SubPath*): WorkspaceReset =
    of(s"${paths.mkString(", ")} in the workspace match the fixture") { build =>
      paths.filter(path => build.filesDifferingFromTheFixture(path).nonEmpty).foreach { path =>
        os.remove.all(build.workspace / path)
        if (os.exists(build.fixtureSource / path)) {
          os.copy(build.fixtureSource / path, build.workspace / path, createFolders = true)
        }
      }
    }(build => paths.flatMap(build.filesDifferingFromTheFixture) shouldBe empty)

  def buildFileHolding(buildText: String): WorkspaceReset =
    of("the build file is as the set-up left it") { build =>
      if (build.buildText != buildText) {
        build.editBuild(_ => buildText)
      }
    }(build => build.buildText shouldBe buildText)

  def jsonOutputFileAsSavedIn(savedCopy: os.SubPath): WorkspaceReset =
    of(s"the JSON output file matches $savedCopy in the workspace") { build =>
      os.copy.over(build.workspace / savedCopy, build.jsonOutputFile)
    } { build =>
      os.isFile(build.jsonOutputFile) shouldBe true
      os.read(build.jsonOutputFile) shouldBe os.read(build.workspace / savedCopy)
    }
}

package org.scalatest.mill

import java.io.IOException
import java.nio.file.Files
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

final class JvmTestModuleRunSpec extends AnyWordSpec with should.Matchers {

  private val label: TestRunLabel = TestRunLabel("core.test")

  private def forkGroupAt(position: Int): PlannedForkGroup =
    PlannedForkGroup(ForkGroup.at(position), ForkGroupLabel(position.toString), PathUnderModule(s"group-$position"), Nil, 1, Nil)

  private def layOutAllButTheFirst(forkGroup: PlannedForkGroup): String =
    if (forkGroup == forkGroupAt(0))
      throw new IllegalStateException("disk full")
    else
      s"queue of ${forkGroup.folder.render}"

  private def outputOf(consolidate: Consolidation => Unit): Seq[String] = {
    val consolidationUnderTest = new ConsolidationUnderTest
    consolidate(consolidationUnderTest.consolidation)
    consolidationUnderTest.consolidation.end()
    consolidationUnderTest.output
  }

  "Laying out a module's workers" when {
    "two fork groups would share a processFolder" should {
      val sharing = ForkGroupsShareProcessFolders(Seq(SharedProcessFolder(Seq(ForkGroup.at(0), ForkGroup.at(1)), PathUnderModule("samples.A1"))))

      "attach the groups and the folder to the root, naming the module" in {
        outputOf(consolidation => JvmTestModuleRun.layOutWorkers(consolidation, label)(Left(sharing))) should contain(
          "    Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1"
        )
      }

      "give nothing to launch" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.layOutWorkers(consolidation, label)(Left(sharing)) shouldBe None
      }
    }

    "checking whether the filesystem ignores case fails" should {
      "attach why the check failed to the root, naming the module" in {
        val checkFailure = new JvmTestModuleRun.FilesystemCaseCheckFailure(new IOException("disk full"))
        outputOf(consolidation => JvmTestModuleRun.layOutWorkers(consolidation, label)(throw checkFailure)) should contain(
          "    Laying out the workers of core.test failed: checking whether the filesystem ignores case failed: java.io.IOException: disk full"
        )
      }
    }

    "the workers are laid out" should {
      "give the launcher, attaching nothing to the root" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.layOutWorkers(consolidation, label)(Right("launcher")) shouldBe Some("launcher")
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.Ended
      }
    }
  }

  "Checking whether the filesystem ignores case" when {
    "the folder exists" should {
      "give whether a file is found under its name in upper case, and leave the folder empty" in {
        val folder = Files.createTempDirectory("case-check")
        val ignoresCase = JvmTestModuleRun.filesystemIgnoresCase(folder)
        val emptied = Using.resource(Files.list(folder))(_.count() == 0)
        val file = Files.createFile(folder.resolve("file"))
        val foundInUpperCase = Files.exists(folder.resolve("FILE"))
        Files.delete(file)
        Files.delete(folder)
        (ignoresCase, emptied) shouldBe ((foundInUpperCase, true))
      }
    }

    "the folder is missing" should {
      "throw, naming the IOException that caused the failure" in {
        val folder = Files.createTempDirectory("case-check")
        Files.delete(folder)
        the[JvmTestModuleRun.FilesystemCaseCheckFailure] thrownBy {
          JvmTestModuleRun.filesystemIgnoresCase(folder)
        } should have message s"checking whether the filesystem ignores case failed: java.nio.file.NoSuchFileException: ${folder.resolve("case-probe")}"
      }
    }
  }

  "Laying out a module's fork groups" when {
    "laying out the first of two fork groups throws" should {
      "attach what it threw to the root, naming the first fork group, and fail the consolidation" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        JvmTestModuleRun.layOutForkGroups(consolidation, label, Seq(forkGroupAt(0), forkGroupAt(1)))(layOutAllButTheFirst)
        consolidation.end()
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "Consolidated tests:",
          "  *** CONSOLIDATED TESTS ABORTED ***",
          "    Laying out fork group 0 of core.test failed: java.lang.IllegalStateException: disk full"
        )
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }

      "keep only the second fork group, with what laying it out returned" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.layOutForkGroups(consolidation, label, Seq(forkGroupAt(0), forkGroupAt(1)))(layOutAllButTheFirst) shouldBe Seq(forkGroupAt(1) -> "queue of group-1")
      }
    }
  }
}

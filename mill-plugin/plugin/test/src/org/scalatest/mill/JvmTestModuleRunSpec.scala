package org.scalatest.mill

import java.io.IOException
import java.nio.file.Files
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.RunEvent
import org.scalatest.matchers.should
import org.scalatest.mill.WorkerLayoutFailure.ClassGlobsKeepNoTestClass
import org.scalatest.mill.WorkerLayoutFailure.FilesystemCaseNotChecked
import org.scalatest.mill.WorkerLayoutFailure.ForkGroupsShareProcessFolders
import org.scalatest.mill.WorkerRun.Ending.Ended
import org.scalatest.mill.WorkerRun.Ending.Failed
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

final class JvmTestModuleRunSpec extends AnyWordSpec with should.Matchers {

  private val module: ModuleName = ModuleName("core.test")

  private def forkGroupAt(position: Int): PlannedForkGroup =
    PlannedForkGroup(ForkGroup.at(position), ForkGroupLabel.of(position, 2), PathUnderModule(s"group-$position"), Nil, Nil)

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

  "Handling the outcome of laying out a module's workers" when {
    "two fork groups would share a processFolder" should {
      val sharing = ForkGroupsShareProcessFolders(Seq(SharedProcessFolder(Seq(ForkGroup.at(0), ForkGroup.at(1)), PathUnderModule("samples.A1"))))

      "attach the groups and the folder to the root, naming the module" in {
        outputOf(consolidation => JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(sharing))) should contain(
          "    Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1"
        )
      }

      "give nothing to launch" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(sharing)) shouldBe None
      }
    }

    "checking whether the filesystem ignores case fails" should {
      "attach why the check failed to the root, naming the module" in {
        val notChecked = FilesystemCaseNotChecked(new IOException("disk full"))
        outputOf(consolidation => JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(notChecked))) should contain(
          "    Laying out the workers of core.test failed: checking whether the filesystem ignores case failed: java.io.IOException: disk full"
        )
      }
    }

    "the class globs keep no class" should {
      val noClassKept = ClassGlobsKeepNoTestClass(Seq(ClassGlob("samples.Gamma*"), ClassGlob("samples.Fast")))

      "attach to the root the failure Mill's testOnly fails with, unprefixed, and show the module with no run" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(noClassKept))
        consolidation.end()
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "Consolidated tests:",
          "  [core.test]",
          "  *** CONSOLIDATED TESTS ABORTED ***",
          "    Test selector does not match any test: samples.Gamma* samples.Fast",
          "    Run discoveredTestClasses to see available tests"
        )
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }
    }

    "the workers are laid out" should {
      "give the launcher, attaching nothing to the root" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Right("launcher")) shouldBe Some("launcher")
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.Ended
      }
    }
  }

  "Reporting, once a module's workers have ended, that its class globs gave no test result" when {
    val classGlobs = Seq(ClassGlob("samples.Alpha1"), ClassGlob("samples.Beta*"))

    def addRunReading(consolidation: Consolidation, eventLog: Seq[String]): Unit =
      consolidation.addRun(module)(feed => eventLog.foreach(line => feed.add(RunEvent.decode(line))))

    def addRunWithAbortedSuite(consolidation: Consolidation): Unit = {
      val suite = """[{"suiteId":"samples.Alpha1","index":0}]"""
      addRunReading(
        consolidation,
        Seq(
          EventLogs.runStarted,
          s"""{"item":"structure","nodes":[{"id":$suite,"kind":"suite","name":"Alpha1","suiteId":"samples.Alpha1","className":"samples.Alpha1"}]}""",
          s"""{"item":"event","node":$suite,"executions":[1],"event":"SuiteStarting","threadName":"main","timeStamp":1}""",
          s"""{"item":"event","node":$suite,"executions":[1],"event":"SuiteAborted","message":"beforeAll failed","threadName":"main","timeStamp":2}""",
          EventLogs.runEndedCompleted
        )
      )
    }

    def addCompletedRunWithNoSuite(consolidation: Consolidation): Unit =
      addRunReading(consolidation, Seq(EventLogs.runStarted, EventLogs.runEndedCompleted))

    def endedReportingNoTestResultAfterACompletedRunWithNoSuite(everyForkGroupLaidOut: Boolean, workerEndings: Seq[WorkerRun.Ending]): ConsolidationUnderTest = {
      val consolidationUnderTest = new ConsolidationUnderTest
      val consolidation = consolidationUnderTest.consolidation
      addCompletedRunWithNoSuite(consolidation)
      JvmTestModuleRun.reportNoTestResultForClassGlobs(consolidation, module, classGlobs, everyForkGroupLaidOut, workerEndings)
      consolidation.end()
      consolidationUnderTest
    }

    "class globs are given, every fork group of the module was laid out, every worker of it started and ran, and no run of the module reported a test result" should {
      "attach to the root the failure Mill's testOnly fails with, unprefixed, and fail the consolidation" in {
        val consolidationUnderTest = endedReportingNoTestResultAfterACompletedRunWithNoSuite(everyForkGroupLaidOut = true, Seq(Ended, Ended))
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "Consolidated tests:",
          "  [core.test]",
          "  *** CONSOLIDATED TESTS ABORTED ***",
          "    Test selector does not match any test: samples.Alpha1 samples.Beta*",
          "    Run discoveredTestClasses to see available tests"
        )
        consolidationUnderTest.consolidation.state shouldBe ConsolidationState.EndedFailed
      }
    }

    "class globs are given, laying out a fork group of the module failed and no run of the module reported a test result" should {
      "attach nothing to the root" in {
        endedReportingNoTestResultAfterACompletedRunWithNoSuite(everyForkGroupLaidOut = false, Seq(Ended)).outputWithoutSummaryLine shouldBe Seq("[core.test]")
      }
    }

    "class globs are given, a worker of the module failed to start or run and no run of the module reported a test result" should {
      "attach nothing to the root" in {
        endedReportingNoTestResultAfterACompletedRunWithNoSuite(everyForkGroupLaidOut = true, Seq(Ended, Failed)).outputWithoutSummaryLine shouldBe Seq("[core.test]")
      }
    }

    "class globs are given and a run of the module reported a suite as aborted" should {
      "attach nothing to the root" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        addRunWithAbortedSuite(consolidation)
        JvmTestModuleRun.reportNoTestResultForClassGlobs(consolidation, module, classGlobs, everyForkGroupLaidOut = true, Seq(Ended))
        consolidation.end()
        consolidationUnderTest.output.filter(_.contains("Test selector does not match any test")) shouldBe empty
      }
    }

    "no class glob is given and no run of the module reported a test result" should {
      "attach nothing to the root, leaving the consolidation unfailed" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        addCompletedRunWithNoSuite(consolidation)
        JvmTestModuleRun.reportNoTestResultForClassGlobs(consolidation, module, Nil, everyForkGroupLaidOut = true, Seq(Ended))
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.Ended
      }
    }
  }

  "Checking whether the filesystem ignores case" when {
    "the folder exists" should {
      "give whether a file is found under its name in upper case, and leave the folder empty" in {
        TemporaryFolder.within("case-check") { folder =>
          val ignoresCase = JvmTestModuleRun.filesystemIgnoresCase(folder)
          val emptied = Using.resource(Files.list(folder))(_.count() == 0)
          Files.createFile(folder.resolve("file"))
          val foundInUpperCase = Files.exists(folder.resolve("FILE"))
          (ignoresCase, emptied) shouldBe ((Right(foundInUpperCase), true))
        }
      }
    }

    "the folder is missing" should {
      "give the failure, naming the IOException that caused it" in {
        TemporaryFolder.within("case-check") { parent =>
          val folder = parent.resolve("missing")
          JvmTestModuleRun.filesystemIgnoresCase(folder).left.map(_.message) shouldBe Left(
            s"checking whether the filesystem ignores case failed: java.nio.file.NoSuchFileException: ${folder.resolve("case-probe")}"
          )
        }
      }
    }
  }

  "Laying out a module's fork groups" when {
    "laying out the first of two fork groups throws" should {
      "attach what it threw to the root, naming the first fork group, and fail the consolidation" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        JvmTestModuleRun.layOutForkGroups(consolidation, module, Seq(forkGroupAt(0), forkGroupAt(1)))(layOutAllButTheFirst)
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
        JvmTestModuleRun.layOutForkGroups(consolidation, module, Seq(forkGroupAt(0), forkGroupAt(1)))(layOutAllButTheFirst) shouldBe Seq(forkGroupAt(1) -> "queue of group-1")
      }
    }
  }
}

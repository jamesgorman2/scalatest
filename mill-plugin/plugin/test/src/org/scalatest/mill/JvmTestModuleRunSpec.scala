package org.scalatest.mill

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.RunEvent
import org.scalatest.matchers.should
import org.scalatest.mill.WorkerLayoutFailure.ClassGlobsKeepNoTestClass
import org.scalatest.mill.WorkerLayoutFailure.FilesystemCaseNotChecked
import org.scalatest.mill.WorkerLayoutFailure.ForkGroupsShareProcessFolders
import org.scalatest.mill.WorkerRun.Ending.EndedWithResult
import org.scalatest.mill.WorkerRun.Ending.EndedWithoutResult
import org.scalatest.mill.WorkerRun.Ending.Failed
import org.scalatest.mill.WorkerRun.Ending.StartedNoProcess
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

  private def consolidationEndedAfter(consolidate: Consolidation => Unit): ConsolidationUnderTest = {
    val consolidationUnderTest = new ConsolidationUnderTest
    consolidate(consolidationUnderTest.consolidation)
    consolidationUnderTest.consolidation.end()
    consolidationUnderTest
  }

  "Handling the outcome of laying out a module's workers" when {
    "two fork groups would share a processFolder" should {
      val sharing = ForkGroupsShareProcessFolders(Seq(SharedProcessFolder(Seq(ForkGroup.at(0), ForkGroup.at(1)), PathUnderModule("samples.A1"))))

      "attach the groups and the folder to the module, without naming the module" in {
        consolidationEndedAfter(consolidation => JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(sharing))).outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  *** MODULE ABORTED ***",
          "    Laying out the workers failed: fork groups 0 and 1 share the processFolder samples.A1"
        )
      }

      "give nothing to launch" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(sharing)) shouldBe None
      }
    }

    "checking whether the filesystem ignores case fails" should {
      "attach why the check failed to the module, without naming the module" in {
        val notChecked = FilesystemCaseNotChecked(new IOException("disk full"))
        consolidationEndedAfter(consolidation => JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(notChecked))).outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  *** MODULE ABORTED ***",
          "    Laying out the workers failed: checking whether the filesystem ignores case failed: java.io.IOException: disk full"
        )
      }
    }

    "the class globs keep no class" should {
      val noClassKept = ClassGlobsKeepNoTestClass(Seq(ClassGlob("samples.Gamma*"), ClassGlob("samples.Fast")))

      "attach to the module the failure Mill's testOnly fails with, unprefixed, and show the module with no run" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        JvmTestModuleRun.launcherOrReportFailure(consolidation, module, Left(noClassKept))
        consolidation.end()
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  *** MODULE ABORTED ***",
          "    Test selector does not match any test: samples.Gamma* samples.Fast",
          "    Run discoveredTestClasses to see available tests"
        )
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }
    }

    "the workers are laid out" should {
      "give the launcher, attaching nothing to the module" in {
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

    def addUnfinishedRunWithNoSuite(consolidation: Consolidation): Unit =
      addRunReading(consolidation, Seq(EventLogs.runStarted))

    def consolidationEndedAfterReportingNoTestResult(
        addRun: Consolidation => Unit,
        everyForkGroupLaidOut: Boolean,
        workerEndings: Seq[WorkerRun.Ending]
    ): ConsolidationUnderTest =
      consolidationEndedAfter { consolidation =>
        addRun(consolidation)
        JvmTestModuleRun.reportNoTestResultForClassGlobs(consolidation, module, classGlobs, everyForkGroupLaidOut, workerEndings)
      }

    Seq(
      "every worker of it wrote its result or started no process" -> Seq(EndedWithResult, StartedNoProcess),
      "the JVM of a worker of it ended without writing its result after its run completed" -> Seq(EndedWithResult, EndedWithoutResult)
    ).foreach { case (howTheWorkersEnded, workerEndings) =>
      s"class globs are given, every fork group of the module was laid out, $howTheWorkersEnded, and no run of the module reported a test result" should {
        "attach to the module the failure Mill's testOnly fails with, unprefixed, and fail the consolidation" in {
          val consolidationUnderTest = consolidationEndedAfterReportingNoTestResult(addCompletedRunWithNoSuite, everyForkGroupLaidOut = true, workerEndings)
          consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
            "[core.test]",
            "  *** MODULE ABORTED ***",
            "    Test selector does not match any test: samples.Alpha1 samples.Beta*",
            "    Run discoveredTestClasses to see available tests"
          )
          consolidationUnderTest.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "class globs are given, every fork group of the module was laid out, every worker of it wrote its result, a run of the module did not finish, and no run of the module reported a test result" should {
      "attach to the module the failure Mill's testOnly fails with" in {
        val consolidationUnderTest =
          consolidationEndedAfterReportingNoTestResult(addUnfinishedRunWithNoSuite, everyForkGroupLaidOut = true, Seq(EndedWithResult, EndedWithResult))
        consolidationUnderTest.output should contain("    Test selector does not match any test: samples.Alpha1 samples.Beta*")
      }
    }

    "class globs are given, a fork group of the module failed to lay out and no run of the module reported a test result" should {
      "attach nothing to the module" in {
        val consolidationUnderTest = consolidationEndedAfterReportingNoTestResult(addCompletedRunWithNoSuite, everyForkGroupLaidOut = false, Seq(EndedWithResult))
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq("[core.test]")
      }
    }

    "class globs are given, a worker of the module failed to start or run and no run of the module reported a test result" should {
      "attach nothing to the module" in {
        val consolidationUnderTest = consolidationEndedAfterReportingNoTestResult(addCompletedRunWithNoSuite, everyForkGroupLaidOut = true, Seq(EndedWithResult, Failed))
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq("[core.test]")
      }
    }

    "class globs are given, the JVM of a worker of the module ended without writing its result before its run finished, and no run of the module reported a test result" should {
      "not attach the failure Mill's testOnly fails with" in {
        val consolidationUnderTest =
          consolidationEndedAfterReportingNoTestResult(addUnfinishedRunWithNoSuite, everyForkGroupLaidOut = true, Seq(EndedWithResult, EndedWithoutResult))
        consolidationUnderTest.output.filter(_.contains("Test selector does not match any test")) shouldBe empty
      }
    }

    "class globs are given, the JVM of a worker of the module ended without writing its result before any run existed in it, and no run of the module reported a test result" should {
      "not attach the failure Mill's testOnly fails with" in {
        val consolidationUnderTest =
          consolidationEndedAfterReportingNoTestResult(_.addProcessEndedBeforeAnyRun(module), everyForkGroupLaidOut = true, Seq(EndedWithoutResult))
        consolidationUnderTest.output.filter(_.contains("Test selector does not match any test")) shouldBe empty
      }
    }

    "class globs are given, only another module has ended abnormally, the JVM of a worker of the module ended without writing its result after its run completed, and no run of the module reported a test result" should {
      "attach to the module the failure Mill's testOnly fails with" in {
        val consolidationUnderTest = consolidationEndedAfterReportingNoTestResult(
          consolidation => {
            consolidation.addModuleFailure(ModuleName("other.test"), "other.test.compile failed")
            addCompletedRunWithNoSuite(consolidation)
          },
          everyForkGroupLaidOut = true,
          Seq(EndedWithResult, EndedWithoutResult)
        )
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  *** MODULE ABORTED ***",
          "    Test selector does not match any test: samples.Alpha1 samples.Beta*",
          "    Run discoveredTestClasses to see available tests",
          "[other.test]",
          "  *** MODULE ABORTED ***",
          "    other.test.compile failed"
        )
      }
    }

    "class globs are given and a run of the module reported a suite as aborted" should {
      "not attach the failure Mill's testOnly fails with" in {
        val consolidationUnderTest = consolidationEndedAfterReportingNoTestResult(addRunWithAbortedSuite, everyForkGroupLaidOut = true, Seq(EndedWithResult))
        consolidationUnderTest.output.filter(_.contains("Test selector does not match any test")) shouldBe empty
      }
    }

    "no class glob is given and no run of the module reported a test result" should {
      "attach nothing to the module, leaving the consolidation unfailed" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        addCompletedRunWithNoSuite(consolidation)
        JvmTestModuleRun.reportNoTestResultForClassGlobs(consolidation, module, Nil, everyForkGroupLaidOut = true, Seq(EndedWithResult))
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.Ended
      }
    }
  }

  "Capturing what a module's test discovery writes" when {
    "discovery writes lines to Console and to the streams it is given" should {
      "attach each line to the module, tagged with its stream, in the order written" in {
        consolidationEndedAfter { consolidation =>
          JvmTestModuleRun.attachingDiscoveryOutput(consolidation, module, StandardCharsets.UTF_8) { (stdout, stderr) =>
            Console.out.println("loading samples.Noisy")
            stderr.println("warn: slow discovery")
            stdout.print("last line")
          }
        }.outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  [stdout] loading samples.Noisy",
          "  [stderr] warn: slow discovery",
          "  [stdout] last line"
        )
      }

      "give what discovery returns" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.attachingDiscoveryOutput(consolidation, module, StandardCharsets.UTF_8)((_, _) => Set("samples.Noisy")) shouldBe Set("samples.Noisy")
      }
    }

    "discovery throws after writing a line" should {
      "attach that line to the module and rethrow what discovery threw" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        val thrown = the[ExceptionInInitializerError] thrownBy {
          JvmTestModuleRun.attachingDiscoveryOutput(consolidation, module, StandardCharsets.UTF_8) { (_, _) =>
            Console.out.println("loading samples.Noisy")
            throw new ExceptionInInitializerError("samples.Noisy failed to initialize")
          }
        }
        consolidation.end()
        (thrown.getMessage, consolidationUnderTest.outputWithoutSummaryLine) shouldBe
          (("samples.Noisy failed to initialize", Seq("[core.test]", "  [stdout] loading samples.Noisy")))
      }
    }

    "a thread that discovery started writes after discovery returns" should {
      "attach nothing that thread writes to the module" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        val discoveryReturned = new CountDownLatch(1)
        val lateWriter = JvmTestModuleRun.attachingDiscoveryOutput(consolidation, module, StandardCharsets.UTF_8) { (_, _) =>
          Console.out.println("during discovery")
          val writer = new Thread(() => {
            discoveryReturned.await()
            Console.out.println("after discovery")
            Console.err.println("after discovery")
          })
          writer.start()
          writer
        }
        discoveryReturned.countDown()
        lateWriter.join()
        consolidation.end()
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq("[core.test]", "  [stdout] during discovery")
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
      "attach what it threw to the module, naming the first fork group, and fail the consolidation" in {
        val consolidationUnderTest = new ConsolidationUnderTest
        val consolidation = consolidationUnderTest.consolidation
        JvmTestModuleRun.layOutForkGroups(consolidation, module, Seq(forkGroupAt(0), forkGroupAt(1)))(layOutAllButTheFirst)
        consolidation.end()
        consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
          "[core.test]",
          "  *** MODULE ABORTED ***",
          "    Laying out fork group 0 failed: java.lang.IllegalStateException: disk full"
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

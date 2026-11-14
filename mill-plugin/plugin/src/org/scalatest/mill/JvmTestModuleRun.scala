package org.scalatest.mill

import mill.api.TaskCtx
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import scala.concurrent.duration.DurationInt

/**
 * Runs a JVM test module's tests in one worker started in `moduleFolder`, through Mill's test queue, and adds the
 * worker's run to a consolidation.
 */
private[mill] final class JvmTestModuleRun(
    label: TestRunLabel,
    inputs: JvmTestModuleInputs,
    moduleFolder: os.Path,
    ctx: TaskCtx
) {

  private val processFolder: os.Path = moduleFolder
  private val eventLogFile: os.Path = processFolder / JvmTestModuleRun.eventLogFileName

  def run(consolidation: Consolidation): Unit = {
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    val testClasses = discoveredTestClasses()
    val reader = new EventLogReader(eventLogFile.toNIO, () => consolidation.addRun(label), ProcessExit.hasExited, 10.millis)
    reader.start()
    try {
      startWorker(testClasses)
    } finally {
      reader.awaitStreamEnd()
    }
  }

  private def discoveredTestClasses(): Seq[String] = {
    val discovered = inputs.jvmWorker
      .apply(
        ZincOp.GetTestTasks(
          (inputs.runClasspath ++ inputs.testrunnerEntrypointClasspath).map(_.path),
          inputs.testClasspath.map(_.path),
          inputs.testFramework,
          Nil,
          Nil,
          inputs.discoveredClasses
        ),
        javaHome = inputs.javaHome
      )(using ctx)
      .toSet
    inputs.forkGroups.map(_.filter(discovered)).filter(_.nonEmpty) match {
      case Seq() => Nil
      case Seq(group) => group
      case groups => ctx.fail(s"$label has ${groups.size} fork groups, but the test command runs one")
    }
  }

  // The worker's stdout and stderr are kept off the console. Mill's worker writes ScalaTest's end-of-run summary there.
  private def startWorker(testClasses: Seq[String]): Unit = {
    val util = inputs.testModuleUtil(RunnerArgument.forEventLogFile(eventLogFile.toNIO), ctx)
    val queue = util.prepareTestClassesFolder(testClasses, processFolder)
    val claimFolder = processFolder / "claim"
    os.makeDir.all(claimFolder)
    val discarded = os.ProcessOutput.ReadBytes((_, _) => ())
    os.Inherit.out.withValue(discarded) {
      os.Inherit.err.withValue(discarded) {
        util.callTestRunnerSubprocess(processFolder, processFolder / "result.log", Right((None, queue, claimFolder)), () => ())(using ctx)
      }
    }
    ()
  }
}

private[mill] object JvmTestModuleRun {

  /** The event log file's name in a worker's `processFolder`. Mill names no file there so. */
  val eventLogFileName: String = "scalatest-event-log.jsonl"
}

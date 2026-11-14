package org.scalatest.mill

import mill.api.TaskCtx
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import scala.concurrent.duration.DurationInt

/**
 * Runs a JVM test module's tests in one worker started in `moduleFolder`, through Mill's test queue. Adds the worker's
 * run, with the lines captured from its stdout and stderr, to a consolidation, or the worker itself where no run
 * existed in it.
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
    val capture = new WorkerOutputCapture(WorkerOutputCapture.workerCharset)
    val reader = new EventLogReader(
      eventLogFile.toNIO,
      () => {
        val feed = consolidation.addRun(label)
        capture.addTo(feed.add)
        feed
      },
      () => consolidation.addProcessEndedBeforeAnyRun(),
      ProcessExit.hasExited,
      10.millis
    )
    reader.start()
    try {
      startWorker(testClasses, capture)
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

  // The worker's result is ignored, even a failure: the consolidation decides the test command's result.
  // Mill's call spawns the worker on this thread, reading `os.Inherit` here, and returns once both streams are read.
  private def startWorker(testClasses: Seq[String], capture: WorkerOutputCapture): Unit = {
    val util = inputs.testModuleUtil(RunnerArgument.forEventLogFile(eventLogFile.toNIO), ctx)
    val queue = util.prepareTestClassesFolder(testClasses, processFolder)
    val claimFolder = processFolder / "claim"
    os.makeDir.all(claimFolder)
    os.Inherit.out.withValue(os.ProcessOutput.ReadBytes(capture.stdout)) {
      os.Inherit.err.withValue(os.ProcessOutput.ReadBytes(capture.stderr)) {
        util.callTestRunnerSubprocess(processFolder, processFolder / "result.log", Right((None, queue, claimFolder)), () => ())(using ctx)
      }
    }
    capture.endStreams()
  }
}

private[mill] object JvmTestModuleRun {

  /** The event log file's name in a worker's `processFolder`. Mill uses no file of that name there. */
  val eventLogFileName: String = "scalatest-event-log.jsonl"
}

package org.scalatest.tools

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.Assertions
import org.scalatest.events.BuiltEvent
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import sbt.testing.Event as SbtEvent
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Runner as SbtRunner
import sbt.testing.Status as SbtStatus
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import scala.jdk.CollectionConverters.*

private[tools] enum Launch(val named: String) {
  case ByFramework extends Launch("Framework")
  case ByRunner extends Launch("Runner.run")
}

private[tools] final class RecordingLogger extends Logger {
  private var received = Vector.empty[String]
  def lines: Vector[String] = synchronized(received)
  private def record(line: String): Unit = synchronized { received :+= line }
  def ansiCodesSupported(): Boolean = false
  def error(message: String): Unit = record(message)
  def warn(message: String): Unit = record(message)
  def info(message: String): Unit = record(message)
  def debug(message: String): Unit = record(message)
  def trace(throwable: Throwable): Unit = record(String.valueOf(throwable))
}

/** Launches runs given the runner argument, and reads the event logs they write. */
private[tools] trait TestEventLogRuns { this: Assertions =>

  protected val runTimeLimitSeconds = 120L

  protected def built(notation: String): Vector[BuiltEvent] =
    TestRunBuilder.build(TestRunPart.Events(notation)).fold(reason => fail(reason), _.events)

  protected def decodedOrFail(lines: Seq[String]): Vector[TestEventLogEvent] =
    lines.toVector.map(line => TestEventLogDecoder.decode(line).fold(reason => fail(s"$reason, in $line"), identity))

  protected def eventLogOf(eventLogFile: Path): Vector[TestEventLogEvent] = decodedOrFail(TemporaryEventLog.lines(eventLogFile))

  protected def thisProcess: ProcessIdentity = {
    val current = ProcessIdentity.current
    assume(current.isRight, s"an emitter needs its process's identity: ${current.left.getOrElse("")}")
    current.fold(reason => fail(reason), identity)
  }

  protected def runnerArgument(eventLogFile: Path): List[String] = TestEventLogArgument.forFile(eventLogFile.toString)

  // Runner.run blocks while a graphic reporter's window is open, so a run that wrongly opens one fails at the time limit.
  protected def runnerRun(arguments: Seq[String]): Boolean = {
    val outcome = new AtomicReference[Option[Either[Throwable, Boolean]]](None)
    val run = new Thread(() => {
      outcome.set(Some(try Right(Runner.run(arguments.toArray)) catch { case thrown: Throwable => Left(thrown) }))
    })
    run.setDaemon(true)
    run.start()
    run.join(TimeUnit.SECONDS.toMillis(runTimeLimitSeconds))
    outcome.get match {
      case Some(Right(allTestsPassed)) => allTestsPassed
      case Some(Left(thrown)) => throw thrown
      case None =>
        run.interrupt()
        java.awt.Window.getWindows.foreach(_.dispose())
        fail(s"Runner.run did not return within $runTimeLimitSeconds seconds")
    }
  }

  protected def runpathOf(folder: File): String = folder.getPath.replace(" ", "\\ ")

  protected def testClassesRunpath: String =
    runpathOf(new File(classOf[samples.SqrtPropSpec].getProtectionDomain.getCodeSource.getLocation.toURI))

  protected def frameworkRunner(arguments: Seq[String], remoteArguments: Seq[String] = Nil): SbtRunner =
    new Framework().runner(arguments.toArray, remoteArguments.toArray, getClass.getClassLoader)

  /** Executes the suite's task and the tasks it returns, and returns the status of each test-interface event. */
  protected def execute(runner: SbtRunner, suiteClassName: String, logger: Logger): Vector[SbtStatus] = {
    val statuses = new ConcurrentLinkedQueue[SbtStatus]
    val recordingStatuses: EventHandler = (event: SbtEvent) => statuses.add(event.status)
    val fingerprint = new Framework().fingerprints.collectFirst { case subclass: SubclassFingerprint => subclass }.get
    var tasks = runner.tasks(Array(new TaskDef(suiteClassName, fingerprint, true, Array(new SuiteSelector)))).toList
    while (tasks.nonEmpty) {
      tasks = tasks.head.execute(recordingStatuses, Array(logger)).toList ::: tasks.tail
    }
    statuses.asScala.toVector
  }

  /** Runs the suite to the run's end, and returns whether every test passed. */
  protected def launched(launch: Launch, suiteClassName: String, arguments: Seq[String]): Boolean = {
    launch match {
      case Launch.ByFramework =>
        val runner = frameworkRunner(arguments)
        val statuses = execute(runner, suiteClassName, new RecordingLogger)
        runner.done()
        statuses.forall(_ == SbtStatus.Success)
      case Launch.ByRunner =>
        runnerRun(List("-R", testClassesRunpath, "-s", suiteClassName) ++ arguments)
    }
  }

  // Discovery reads every class of a runpath, so the suite is given a runpath that holds nothing else.
  protected def withRunpathOfOneSuite[A](folder: Path)(body: String => A): A = {
    val classFile = "samples/discovered/OneTestSpec.class"
    val copy = folder.resolve(classFile)
    Files.createDirectories(copy.getParent)
    val source = getClass.getClassLoader.getResourceAsStream(classFile)
    try Files.copy(source, copy) finally source.close()
    body(runpathOf(folder.toFile))
  }
}

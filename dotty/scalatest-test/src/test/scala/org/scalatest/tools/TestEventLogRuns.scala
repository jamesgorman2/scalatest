package org.scalatest.tools

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.Assertions
import org.scalatest.Reporter
import org.scalatest.events.Event
import org.scalatest.events.BuiltEvent
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.NestedSuiteSelector
import sbt.testing.Runner as SbtRunner
import sbt.testing.Selector
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

/**
 * Records into the recording that is current when the run constructs it, so a run that outlives its recording,
 * having timed out, cannot write into a later one.
 */
final class RunRecordingReporter extends Reporter {
  private val recording = RunRecordingReporter.current.get
  def apply(event: Event): Unit = recording.add(event)
}

object RunRecordingReporter {

  private val current = new AtomicReference(new ConcurrentLinkedQueue[Event])

  /**
   * Every run given a `RunRecordingReporter` must be launched here: specs run in parallel, and a run launched elsewhere
   * would record into the recording of whichever caller holds the lock.
   */
  def eventsReportedIn(launch: => Unit): Vector[Event] = synchronized {
    val recording = new ConcurrentLinkedQueue[Event]
    current.set(recording)
    launch
    recording.asScala.toVector
  }
}

private[tools] final case class JvmOutput(stdout: Vector[String], stderr: Vector[String])

object FrameworkRunInItsOwnJvm extends Assertions with TestEventLogRuns {

  def main(arguments: Array[String]): Unit = launched(Launch.ByFramework, arguments.head, arguments.tail.toSeq)
}

private[tools] trait TestEventLogRuns { this: Assertions =>

  protected val runTimeLimitSeconds = 120L

  protected def built(notation: String): Vector[BuiltEvent] =
    TestRunBuilder.build(TestRunPart.Events(notation)).fold(reason => fail(reason), _.events)

  protected def decodedOrFail(lines: Seq[String]): Vector[TestEventLogEvent] =
    lines.toVector.map(line => TestEventLogDecoder.decode(line).fold(reason => fail(s"$reason, in $line"), identity))

  protected def eventLogOf(eventLogFile: Path): Vector[TestEventLogEvent] = decodedOrFail(TemporaryEventLog.lines(eventLogFile))

  protected def classNamesGivenTo(suiteName: String, events: Seq[TestEventLogEvent]): Vector[String] =
    events.toVector
      .collect { case TestEventLogEvent.Structure(nodes) => nodes }
      .flatMap(_.collect { case suite: StructureNode.Suite if suite.name == suiteName => suite.className })

  protected def thisProcess: ProcessIdentity = {
    val current = ProcessIdentity.current
    assume(current.isRight, s"an emitter needs its process's identity: ${current.left.getOrElse("")}")
    current.fold(reason => fail(reason), identity)
  }

  protected def runnerArgument(eventLogFile: Path): List[String] = TestEventLogArgument.forFile(eventLogFile.toString)

  // Runner.run blocks while a graphic reporter's window is open, so a run that wrongly opens one fails at the time limit.
  // Runner.run renames its thread, so a sample that must tell that thread apart is given the name of its thread group.
  // A run that times out is left running: Runner.run constructs its reporters before it runs a suite, so a recording
  // reporter it was given still records into the recording that has ended.
  protected def runnerRun(arguments: Seq[String], threadGroupName: Option[String] = None): Boolean = {
    val outcome = new AtomicReference[Option[Either[Throwable, Boolean]]](None)
    val threadGroup = threadGroupName.map { name =>
      val group = new ThreadGroup(name)
      group.setDaemon(true)
      group
    }
    val run = new Thread(
      threadGroup.orNull,
      () => {
        outcome.set(Some(try Right(Runner.run(arguments.toArray)) catch { case thrown: Throwable => Left(thrown) }))
      },
      "runs Runner.run"
    )
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

  /**
   * Framework, given a suite by name, runs none of the tests of the suites it nests, unless the task also selects
   * those suites by their suite IDs.
   */
  protected def execute(runner: SbtRunner, suiteClassName: String, eventHandler: EventHandler, logger: Logger, nestedSuiteIds: Seq[String]): Unit = {
    val fingerprint = new Framework().fingerprints.collectFirst { case subclass: SubclassFingerprint => subclass }.get
    val selectors = Array[Selector](new SuiteSelector) ++ nestedSuiteIds.map(suiteId => new NestedSuiteSelector(suiteId))
    var tasks = runner.tasks(Array(new TaskDef(suiteClassName, fingerprint, true, selectors))).toList
    while (tasks.nonEmpty) {
      tasks = tasks.head.execute(eventHandler, Array(logger)).toList ::: tasks.tail
    }
  }

  protected def execute(runner: SbtRunner, suiteClassName: String, logger: Logger, nestedSuiteIds: Seq[String]): Unit =
    execute(runner, suiteClassName, _ => (), logger, nestedSuiteIds)

  protected def execute(runner: SbtRunner, suiteClassName: String, logger: Logger): Unit = execute(runner, suiteClassName, logger, Nil)

  /** `nestedSuiteIds` apply under Framework only. */
  protected def launched(launch: Launch, suiteClassName: String, arguments: Seq[String], nestedSuiteIds: Seq[String] = Nil): Unit = {
    launch match {
      case Launch.ByFramework =>
        val runner = frameworkRunner(arguments)
        try execute(runner, suiteClassName, new RecordingLogger, nestedSuiteIds)
        finally runner.done()
      case Launch.ByRunner =>
        runnerRun(List("-R", testClassesRunpath, "-s", suiteClassName) ++ arguments)
    }
  }

  protected def eventsReportedWhenLaunched(
    launch: Launch,
    suiteClassName: String,
    arguments: Seq[String],
    nestedSuiteIds: Seq[String] = Nil
  ): Vector[Event] = {
    RunRecordingReporter.eventsReportedIn(
      launched(launch, suiteClassName, arguments ++ List("-C", classOf[RunRecordingReporter].getName), nestedSuiteIds)
    )
  }

  private def classpathOf(loader: ClassLoader): List[URL] = {
    loader match {
      case null => Nil
      case ofUrls: URLClassLoader => ofUrls.getURLs.toList ::: classpathOf(ofUrls.getParent)
      case other => classpathOf(other.getParent)
    }
  }

  protected def outputOfFrameworkRunInItsOwnJvm(suiteClassName: String, arguments: Seq[String], folder: Path): JvmOutput =
    outputOfItsOwnJvm(FrameworkRunInItsOwnJvm.getClass.getName.stripSuffix("$"), suiteClassName +: arguments, folder)

  /** The JVM runs on this JVM's classpath, and writes its stdout and stderr to files in `folder`. */
  protected def outputOfItsOwnJvm(mainClassName: String, arguments: Seq[String], folder: Path): JvmOutput = {
    val classpath =
      (classpathOf(getClass.getClassLoader).map(url => Paths.get(url.toURI).toString) ++
        System.getProperty("java.class.path").split(File.pathSeparator)).distinct.mkString(File.pathSeparator)
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val stdout = folder.resolve("stdout.txt")
    val stderr = folder.resolve("stderr.txt")
    val command = List(java, "-cp", classpath, mainClassName) ++ arguments
    val jvm = new ProcessBuilder(command.asJava).redirectOutput(stdout.toFile).redirectError(stderr.toFile).start()
    def linesOf(file: Path): Vector[String] = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).linesIterator.toVector
    try {
      if (!jvm.waitFor(runTimeLimitSeconds, TimeUnit.SECONDS)) {
        fail(s"the JVM did not end within $runTimeLimitSeconds seconds")
      }
      if (jvm.exitValue != 0) {
        fail(s"the JVM ended with exit code ${jvm.exitValue}, having written to stderr:\n${linesOf(stderr).mkString("\n")}")
      }
    } finally {
      jvm.destroyForcibly()
      jvm.waitFor(runTimeLimitSeconds, TimeUnit.SECONDS)
    }
    JvmOutput(linesOf(stdout), linesOf(stderr))
  }

  protected def withRunpathOfOneSuite[A](folder: Path)(body: String => A): A =
    withRunpathOf(folder, List("samples/discovered/OneTestSpec.class"))(body)

  // Discovery reads every class of a runpath, so the suites are given a runpath that holds nothing but their class files.
  protected def withRunpathOf[A](folder: Path, classFiles: Seq[String])(body: String => A): A = {
    classFiles.foreach { classFile =>
      val copy = folder.resolve(classFile)
      Files.createDirectories(copy.getParent)
      val source = getClass.getClassLoader.getResourceAsStream(classFile)
      try Files.copy(source, copy) finally source.close()
    }
    body(runpathOf(folder.toFile))
  }
}

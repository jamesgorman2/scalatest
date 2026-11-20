package org.scalatest.consolidation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.scalatest.Assertions
import org.scalatest.tools.Framework
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Selector
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Runs a suite through `Framework`, outside Mill, writing the run's test event log, and consolidates that log. */
private[consolidation] trait EmittedRuns { this: Assertions & ConsolidatedRuns & JsonOutputs =>

  private val ignoringEvents: EventHandler = _ => ()

  def eventLogOfFrameworkRun(suiteClassName: String, runnerArguments: Seq[String] = Nil): Vector[RunEvent] = {
    val folder = Files.createTempDirectory("emitted-run")
    try {
      val eventLogFile = folder.resolve("events.log")
      val framework = new Framework
      val runner = framework.runner((RunnerArgument.forEventLogFile(eventLogFile) ++ runnerArguments).toArray, Array.empty, getClass.getClassLoader)
      try {
        val taskDef = framework.fingerprints
          .collectFirst { case subclass: SubclassFingerprint => new TaskDef(suiteClassName, subclass, true, Array[Selector](new SuiteSelector)) }
          .getOrElse(fail("the framework has no subclass fingerprint"))
        executeAll(runner.tasks(Array(taskDef)).toList)
      } finally runner.done()
      new String(Files.readAllBytes(eventLogFile), StandardCharsets.UTF_8).split('\n').toVector.map(RunEvent.decode)
    } finally {
      val walk = Files.walk(folder)
      try walk.sorted(Comparator.reverseOrder[Path]()).iterator.asScala.foreach(Files.delete) finally walk.close()
    }
  }

  @tailrec
  private def executeAll(tasks: List[Task]): Unit =
    tasks match {
      case task :: rest => executeAll(task.execute(ignoringEvents, Array.empty[Logger]).toList ::: rest)
      case Nil => ()
    }

  /** The human-readable output's lines, with colour on, less the summary line. */
  def colourMarkedTreeLinesFrom(eventLog: Vector[RunEvent]): Vector[String] =
    colourMarkedOutputAfter(_.addRunOf(eventLog)).lines.dropRight(1)
}

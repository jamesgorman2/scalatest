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
import sbt.testing.TaskDef
import scala.jdk.CollectionConverters.*

/** Runs a suite through `Framework`, outside Mill, writing the run's test event log, and consolidates that log. */
private[consolidation] trait EmittedRuns { this: Assertions & ConsolidatedRuns & JsonOutputs =>

  def eventLogOfFrameworkRun(suiteClassName: String, runnerArguments: Seq[String] = Nil): Vector[String] = {
    val folder = Files.createTempDirectory("emitted-run")
    try {
      val eventLogFile = folder.resolve("events.log")
      val framework = new Framework
      val runner = framework.runner((RunnerArgument.forEventLogFile(eventLogFile) ++ runnerArguments).toArray, Array.empty, getClass.getClassLoader)
      try {
        val fingerprint = framework.fingerprints.collectFirst { case subclass: SubclassFingerprint => subclass }.get
        val ignoringEvents: EventHandler = _ => ()
        var tasks = runner.tasks(Array(new TaskDef(suiteClassName, fingerprint, true, Array[Selector](new SuiteSelector)))).toList
        while (tasks.nonEmpty) {
          tasks = tasks.head.execute(ignoringEvents, Array.empty[Logger]).toList ::: tasks.tail
        }
      } finally runner.done()
      new String(Files.readAllBytes(eventLogFile), StandardCharsets.UTF_8).split('\n').toVector
    } finally {
      val walk = Files.walk(folder)
      try walk.sorted(Comparator.reverseOrder[Path]()).iterator.asScala.foreach(Files.delete) finally walk.close()
    }
  }

  /** The human-readable output's lines, with colour on, less the summary line. */
  def colourMarkedTreeLinesFrom(eventLog: Vector[String]): Vector[String] = {
    val output = new OutputSink
    val consolidation = Consolidation.start(output, coloured = true)
    val feed = consolidation.addRun(moduleLabel)
    eventLog.foreach(line => feed.add(decoded(line)))
    feed.endEventStream()
    consolidation.end()
    output.linesWithoutSummaryLine.map(ConsolidatedRuns.withColoursMarked)
  }

  def jsonOutputOfEventLog(eventLog: Vector[String]): JsonSink = {
    val json = new JsonSink
    val consolidation = startedWithoutMill(json)
    val feed = consolidation.addRun(moduleLabel)
    eventLog.foreach(line => feed.add(decoded(line)))
    feed.endEventStream()
    consolidation.end()
    json
  }

  private def decoded(line: String): RunEvent = RunEvent.decode(line).fold(reason => fail(s"$reason, in $line"), identity)
}

package org.scalatest.consolidation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.Framework
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Selector
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import scala.jdk.CollectionConverters.*

final class EmittedRunSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  /** Runs the suite through `Framework` with the runner argument, and returns the lines of its test event log. */
  private def eventLogOfFrameworkRun(suiteClassName: String): Vector[String] = {
    val folder = Files.createTempDirectory("emitted-run")
    try {
      val eventLogFile = folder.resolve("events.log")
      val framework = new Framework
      val runner = framework.runner(RunnerArgument.forEventLogFile(eventLogFile).toArray, Array.empty, getClass.getClassLoader)
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

  /** The coloured human-readable output of one run, colours marked, each line of its event log decoded and fed in turn. */
  private def consolidatedFrom(eventLog: Vector[String]): Vector[String] = {
    val output = new OutputSink
    val consolidation = Consolidation.start(output, coloured = true)
    val feed = consolidation.addRun(moduleLabel)
    eventLog.foreach(line => feed.add(RunEvent.decode(line).fold(reason => fail(s"$reason, in $line"), identity)))
    feed.endEventStream()
    consolidation.end()
    output.linesApartFromSummaryLine.map(ConsolidatedRuns.withColoursMarked)
  }

  Feature("A failure message reaches the human-readable output unchanged") {

    Scenario("A multi-line, non-ASCII failure message") {
      Given("a suite whose test fails with a multi-line, non-ASCII message")
      And("the suite runs through `Framework`, outside Mill, given the ScalaTest runner argument that writes the run's test event log to a file")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.UnicodeSuite].getName)

      And("the consolidation library is used directly, without Mill, with colour on")
      When("the library consolidates that test event log as a run under the test run label `core.test`")
      val output = consolidatedFrom(eventLog)

      Then("the output is under `core.test`, and the lines under the failed test begin with the message in red, ending with the failing code's file and line")
      val failedLine = output.indexOf("    {red}- reports *** FAILED ***{reset}")
      withClue(output.mkString("\n")) {
        output.headOption shouldBe Some("{cyan}[core.test]{reset}")
        failedLine should be >= 0
        output.slice(failedLine + 1, failedLine + 3).mkString("\n") shouldBe
          """      {red}first line{reset}
            |      {red}second line: ü ß 日本 ✓ (UnicodeSuite.scala:7){reset}""".stripExpectedMargin
      }
    }
  }
}

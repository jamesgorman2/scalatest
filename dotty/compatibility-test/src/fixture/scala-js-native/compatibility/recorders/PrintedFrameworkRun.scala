package compatibility.recorders

import compatibility.normalisation.ReporterText
import org.scalatest.tools.Framework
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

import scala.collection.mutable.ListBuffer

/**
 * A run of the fixture's suites through `Framework`, given the launch's arguments: the suites' class names, separated
 * by commas, then the runner's arguments. Once its tasks have run, it prints to stdout, one line each, what the event
 * handler received, then what the loggers received, then the summary the runner's `done` returns. Logger lines and the
 * summary are normalised as reporter text, which needs every line of a stack trace first.
 */
final class PrintedFrameworkRun(launchArguments: Array[String]) {

  private val eventLines = ListBuffer.empty[String]
  private val loggerLines = ListBuffer.empty[String]
  private val framework = new Framework
  // Scala.js and Native find suite classes by their names alone, and have no class loader to give.
  private val runner = framework.runner(launchArguments.drop(1), Array.empty, null)

  val eventHandler: EventHandler = new RecordingEventHandler(line => eventLines += line)

  val loggers: Array[Logger] = Array(new RecordingLogger(line => loggerLines += line))

  def tasks: Array[Task] = {
    val fingerprint = framework.fingerprints().head
    val suiteClassNames = launchArguments.headOption.fold(Array.empty[String])(_.split(','))
    runner.tasks(suiteClassNames.map(name => new TaskDef(name, fingerprint, false, Array(new SuiteSelector))))
  }

  def printRecorded(): Unit = {
    eventLines.foreach(line => println("event handler: " + line))
    ReporterText.normalised(loggerLines.toList).foreach(line => println("logger: " + line))
    ReporterText.normalised(runner.done().split("\n", -1).toList).foreach(line => println("summary: " + line))
  }
}

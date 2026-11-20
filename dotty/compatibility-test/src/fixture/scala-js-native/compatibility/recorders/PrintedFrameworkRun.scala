package compatibility.recorders

import compatibility.normalisation.ReporterText
import org.scalatest.tools.Framework
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

import scala.collection.mutable.ListBuffer

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
    // Normalised in one call each, because normalisation collapses consecutive stack-trace lines into one.
    ReporterText.normalised(loggerLines.toList).foreach(line => println("logger: " + line))
    ReporterText.normalised(runner.done().split("\r?\n", -1).toList).foreach(line => println("summary: " + line))
  }
}

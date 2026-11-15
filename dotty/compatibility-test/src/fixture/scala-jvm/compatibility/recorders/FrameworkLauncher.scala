package compatibility.recorders

import org.scalatest.tools.Framework
import sbt.testing.AnnotatedFingerprint
import sbt.testing.EventHandler
import sbt.testing.Fingerprint
import sbt.testing.Logger
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

object FrameworkLauncher {

  def main(runnerArguments: Array[String]): Unit = {
    val framework = new Framework
    val loader = getClass.getClassLoader
    val taskDefs = suiteClassNames.map(taskDef(_, framework.fingerprints, loader))
    val runner = framework.runner(runnerArguments, Array.empty, loader)
    val logger = new RecordingLogger(LineFile.namedBy("compatibility.loggerLines").append)
    execute(runner.tasks(taskDefs), new RecordingEventHandler(LineFile.namedBy("compatibility.events").append), logger)
    val summary = LineFile.namedBy("compatibility.summary")
    runner.done().split("\n", -1).foreach(summary.append)
    System.exit(0)
  }

  private def suiteClassNames: Array[String] =
    Option(System.getProperty("compatibility.suites")) match {
      case Some(names) => names.split(',')
      case None => throw new IllegalStateException("System property compatibility.suites must list the suites to run")
    }

  private def taskDef(suiteClassName: String, fingerprints: Array[Fingerprint], loader: ClassLoader): TaskDef = {
    val wrapped = Class.forName(suiteClassName, false, loader).getAnnotations.exists(_.annotationType.getName == "org.scalatest.WrapWith")
    val fingerprint = fingerprints.find(_.isInstanceOf[AnnotatedFingerprint] == wrapped).get
    new TaskDef(suiteClassName, fingerprint, false, Array(new SuiteSelector))
  }

  private def execute(tasks: Array[Task], eventHandler: EventHandler, logger: Logger): Unit =
    tasks.foreach(task => execute(task.execute(eventHandler, Array(logger)), eventHandler, logger))
}

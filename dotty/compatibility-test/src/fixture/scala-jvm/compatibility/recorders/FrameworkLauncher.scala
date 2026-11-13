package compatibility.recorders

import org.scalatest.tools.Framework
import sbt.testing.AnnotatedFingerprint
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Fingerprint
import sbt.testing.Logger
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

object FrameworkLauncher {

  def main(runnerArguments: Array[String]): Unit = {
    val framework = new Framework
    val loader = getClass.getClassLoader
    val taskDefs = suiteClassNames.map(taskDef(_, framework.fingerprints, loader))
    val runner = framework.runner(runnerArguments, Array.empty, loader)
    val logger = new RecordingLogger(LineFile.namedBy("compatibility.loggerLines"))
    execute(runner.tasks(taskDefs), new RecordingEventHandler(LineFile.namedBy("compatibility.events")), logger)
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

final class RecordingEventHandler(events: LineFile) extends EventHandler {

  def handle(event: Event): Unit =
    events.append(
      List(
        "fullyQualifiedName=" + event.fullyQualifiedName,
        "fingerprint=" + rendered(event.fingerprint),
        "selector=" + event.selector,
        "status=" + event.status,
        "throwable=" + (if (event.throwable.isDefined) EventLine.ofThrowable(event.throwable.get) else "none")
      ).mkString(", ")
    )

  private def rendered(fingerprint: Fingerprint): String =
    fingerprint match {
      case subclass: SubclassFingerprint =>
        "subclass(" + subclass.superclassName + ", " + subclass.isModule + ", " + subclass.requireNoArgConstructor + ")"
      case annotated: AnnotatedFingerprint =>
        "annotated(" + annotated.annotationName + ", " + annotated.isModule + ")"
      case other => other.toString
    }
}

final class RecordingLogger(lines: LineFile) extends Logger {

  def ansiCodesSupported(): Boolean = true

  def error(message: String): Unit = record("error", message)

  def warn(message: String): Unit = record("warn", message)

  def info(message: String): Unit = record("info", message)

  def debug(message: String): Unit = record("debug", message)

  def trace(throwable: Throwable): Unit = lines.append("trace: " + EventLine.ofThrowable(throwable))

  private def record(level: String, message: String): Unit =
    message.split("\n", -1).foreach(line => lines.append(level + ": " + line))
}

package compatibility.recorders

import sbt.testing.Task

object PlatformLauncher {

  def main(arguments: Array[String]): Unit = {
    val run = new PrintedFrameworkRun(arguments)
    executeAll(run.tasks, run)
    run.printRecorded()
  }

  private def executeAll(tasks: Array[Task], run: PrintedFrameworkRun): Unit =
    tasks.foreach { task =>
      val more =
        // Scala Native's test bridge runs each task synchronously, and passes what a task throws back to the build tool.
        try {
          task.execute(run.eventHandler, run.loggers)
        } catch {
          case thrown: Throwable =>
            println("task threw: " + EventLine.of(thrown))
            Array.empty[Task]
        }
      executeAll(more, run)
    }
}

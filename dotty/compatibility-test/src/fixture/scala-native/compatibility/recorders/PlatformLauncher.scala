package compatibility.recorders

import sbt.testing.Task

object PlatformLauncher {

  def main(arguments: Array[String]): Unit = {
    val run = new PrintedFrameworkRun(arguments)
    executeAll(run.tasks, run)
    run.printRecorded()
  }

  private def executeAll(tasks: Array[Task], run: PrintedFrameworkRun): Unit =
    // Stands in for Scala Native's test bridge, which runs each task synchronously and passes what a task throws
    // back to the build tool.
    tasks.foreach { task =>
      val more =
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

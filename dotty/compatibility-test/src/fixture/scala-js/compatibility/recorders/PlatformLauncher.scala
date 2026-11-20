package compatibility.recorders

import sbt.testing.Task

import scala.scalajs.js

object PlatformLauncher {

  def main(arguments: Array[String]): Unit = {
    // Node's arguments start with node itself and the program's file.
    val launchArguments = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].toArray.drop(2)
    val run = new PrintedFrameworkRun(launchArguments)
    executeInTurn(run.tasks.toList, run, () => run.printRecorded())
  }

  private def executeInTurn(tasks: List[Task], run: PrintedFrameworkRun, afterLast: () => Unit): Unit =
    tasks match {
      case Nil => afterLast()
      // Scala.js cannot block on a suite's future, so each task runs once the one before it has called back.
      case task :: rest => task.execute(run.eventHandler, run.loggers, (more: Array[Task]) => executeInTurn(more.toList ++ rest, run, afterLast))
    }
}

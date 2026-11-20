package compatibility.recorders

import org.scalatest.tools.Framework
import org.scalatest.tools.Runner

object ArgumentsLauncher {

  def main(launchAndArguments: Array[String]): Unit = {
    val outcome = LineFile.namedBy("compatibility.outcome")
    val arguments = launchAndArguments.tail
    val result =
      try {
        launchAndArguments.head match {
          // Runner.run exits the JVM with status 1, after printing why, on an argument list its first check rejects.
          case "Runner.run" => "returned " + Runner.run(arguments)
          case "Framework" =>
            val runner = new Framework().runner(arguments, Array.empty, getClass.getClassLoader)
            runner.tasks(Array.empty)
            runner.done()
            "accepted"
        }
      } catch {
        case thrown: Throwable => "threw " + EventLine.of(thrown)
      }
    outcome.append(result)
    System.exit(0)
  }
}

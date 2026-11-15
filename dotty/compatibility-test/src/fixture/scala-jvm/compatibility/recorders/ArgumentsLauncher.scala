package compatibility.recorders

import org.scalatest.tools.Framework
import org.scalatest.tools.Runner

/**
 * Gives an argument list to `Runner.run` or to `Framework`'s runner, as its first argument names, and records whether
 * it was accepted. `Framework`'s runner is given no task, so only its arguments are checked. `Runner.run` exits the
 * JVM with status 1, having printed why, for an argument list its first check rejects.
 */
object ArgumentsLauncher {

  def main(launchAndArguments: Array[String]): Unit = {
    val outcome = LineFile.namedBy("compatibility.outcome")
    val arguments = launchAndArguments.tail
    val result =
      try {
        launchAndArguments.head match {
          case "Runner.run" => "returned " + Runner.run(arguments)
          case "Framework" =>
            val runner = new Framework().runner(arguments, Array.empty, getClass.getClassLoader)
            runner.tasks(Array.empty)
            runner.done()
            "accepted"
        }
      } catch {
        case thrown: Throwable => "threw " + EventLine.ofThrowable(thrown)
      }
    outcome.append(result)
    System.exit(0)
  }
}

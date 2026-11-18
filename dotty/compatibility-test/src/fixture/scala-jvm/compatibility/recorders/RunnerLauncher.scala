package compatibility.recorders

import org.scalatest.tools.Runner

object RunnerLauncher {

  def main(arguments: Array[String]): Unit = {
    Runner.run(arguments)
    System.exit(0)
  }
}

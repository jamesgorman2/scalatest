package samples

import java.nio.file.Files
import java.nio.file.Paths
import utest.*

object UtestSuite extends TestSuite {
  val tests: Tests = Tests {
    test("runs") {
      Files.createFile(Paths.get(sys.props("markers")).resolve("UtestSuite.runs"))
    }
  }
}

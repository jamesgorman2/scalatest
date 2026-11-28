package samples

import java.nio.file.Files
import java.nio.file.Paths

final class MunitSuite extends munit.FunSuite {
  test("runs") {
    Files.createFile(Paths.get(sys.props("markers")).resolve("MunitSuite.runs"))
  }
}

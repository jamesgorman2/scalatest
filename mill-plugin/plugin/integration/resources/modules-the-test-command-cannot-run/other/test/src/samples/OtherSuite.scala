package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite

final class OtherSuite extends AnyFunSuite {
  test("runs") {
    Files.createFile(Paths.get(sys.props("markers")).resolve("OtherSuite.runs"))
  }
}

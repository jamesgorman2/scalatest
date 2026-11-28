package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite

final class Alpha1 extends AnyFunSuite {
  test("fast") {
    Files.createFile(Paths.get(sys.props("markers")).resolve("Alpha1.fast"))
  }
}

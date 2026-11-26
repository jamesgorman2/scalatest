package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite

final class NativeSuite extends AnyFunSuite {
  test("runs") {
    Files.createFile(Paths.get(sys.env("MARKERS")).resolve("NativeSuite.runs"))
  }
}

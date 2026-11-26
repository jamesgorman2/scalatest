package samples

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite

abstract class MarkingSuite extends AnyFunSuite {

  override def withFixture(test: NoArgTest): Outcome = {
    val marker = Paths.get(sys.props("markers")).resolve(s"$suiteName.${test.name}")
    Files.writeString(marker, "ran\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    super.withFixture(test)
  }
}

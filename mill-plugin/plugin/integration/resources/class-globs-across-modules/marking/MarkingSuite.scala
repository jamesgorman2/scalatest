package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite

abstract class MarkingSuite extends AnyFunSuite {

  override def withFixture(test: NoArgTest): Outcome = {
    Files.write(Paths.get(sys.props("markers")).resolve(s"$suiteName.${test.name}"), Array.emptyByteArray)
    super.withFixture(test)
  }
}

package samples

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite

abstract class MarkingSuite extends AnyFunSuite {

  override def withFixture(test: NoArgTest): Outcome = {
    val markers = Paths.get(sys.props("markers"))
    Files.writeString(markers.resolve(s"$suiteName.${test.name}"), "ran\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    val outcome = super.withFixture(test)
    if (outcome.isFailed) {
      Files.write(markers.resolve(s"$suiteName.${test.name}.failed"), Array.emptyByteArray)
    }
    outcome
  }
}

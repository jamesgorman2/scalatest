package samples

import org.scalatest.Args
import org.scalatest.Status
import org.scalatest.funsuite.AnyFunSuite

final class RowSuite extends AnyFunSuite {
  override protected def runTest(testName: String, args: Args): Status = {
    super.runTest(testName, args)
    super.runTest(testName, args)
  }

  test("adds") {
    assert(1 + 1 == 2)
  }
}

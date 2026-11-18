package samples

import org.scalatest.Args
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.funsuite.AnyFunSuite

final class SkipSuite extends AnyFunSuite {
  test("runs") {
    assert(1 + 1 == 2)
  }

  test("skipped") {
    assert(1 + 1 == 2)
  }

  override protected def runTest(testName: String, args: Args): Status =
    if (testName == "skipped") SucceededStatus else super.runTest(testName, args)
}

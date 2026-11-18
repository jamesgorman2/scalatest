package samples.discovered

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.propspec.AnyPropSpec

// Discoverable, as OneTestSpec is: a test copies these class files to a runpath of its own for discovery to find.

class AddSuite extends AnyFunSuite {

  test("adds") {
    assert(1 + 1 == 2)
  }
}

class SqrtPropSpec extends AnyPropSpec {

  property("sqrt of a square is the root") {
    assert(math.sqrt(49.0) == 7.0)
  }
}

// Its constructor throws only on a thread of the group a test names for it, so that it constructs wherever else it is discovered.
class BrokenCtorSpec extends AnyFunSuite {

  if (Thread.currentThread.getThreadGroup.getName == BrokenCtorSpec.threadGroupItsConstructorThrowsIn) {
    throw new IllegalStateException("no config")
  }
}

object BrokenCtorSpec {
  val threadGroupItsConstructorThrowsIn = "constructs suites whose constructors throw"
}

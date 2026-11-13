package samples.discovered

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite

// Discoverable, as OneTestSpec is: a test copies these class files to a runpath of its own for discovery to find.

class AddSuite extends AnyFunSuite {

  test("adds") {
    assert(1 + 1 == 2)
  }
}

// Its constructor throws only on a thread of the group a test names for it, so that it constructs wherever else it is discovered.
class BrokenCtorSpec extends AnyFunSpec {

  if (Thread.currentThread.getThreadGroup.getName == BrokenCtorSpec.threadGroupItsConstructorThrowsIn) {
    throw new IllegalStateException("no config")
  }
}

object BrokenCtorSpec {
  val threadGroupItsConstructorThrowsIn = "constructs suites whose constructors throw"
}

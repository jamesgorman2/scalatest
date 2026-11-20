package samples.discovered

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.propspec.AnyPropSpec

// Not @DoNotDiscover: a test copies this package's class files to a runpath of its own for discovery to find.

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

class ThrowingConstructorSpec extends AnyFunSuite {

  // Other runs discover this suite too, and must construct it.
  if (Thread.currentThread.getThreadGroup.getName == ThrowingConstructorSpec.threadGroupItsConstructorThrowsIn) {
    throw new IllegalStateException("no config")
  }
}

object ThrowingConstructorSpec {
  val threadGroupItsConstructorThrowsIn: String = "constructs suites whose constructors throw"
}

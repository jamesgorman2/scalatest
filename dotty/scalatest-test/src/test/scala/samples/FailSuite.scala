package samples

@org.scalatest.DoNotDiscover
class FailSuite extends org.scalatest.funsuite.AnyFunSuite {

  test("t") {
    fail("boom")
  }
}

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks

/** A suite named as `FailSuite` is, whose test `t` fails otherwise. */
abstract class NamedFailSuite extends AnyFunSuite {
  override def suiteName: String = "FailSuite"
}

@DoNotDiscover
class PropertyCheckFailSuite extends NamedFailSuite with TableDrivenPropertyChecks {

  test("t") {
    forAll(Table("n", 1, 2)) { n =>
      assert(n < 2)
    }
  }
}

@DoNotDiscover
class NullMessageFailSuite extends NamedFailSuite {

  test("t") {
    throw new RuntimeException()
  }
}

final case class Point(x: Int, y: Int)

@DoNotDiscover
class AnalysisFailSuite extends NamedFailSuite {

  test("t") {
    assert(Point(1, 2) == Point(1, 3))
  }
}

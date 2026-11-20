package samples

import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.OneInstancePerTest
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

abstract class ReplacesARanTest extends AnyFunSuite with OneInstancePerTest {

  protected def ranA: AtomicBoolean

  if (ranA.get) {
    test("c") {
      succeed
    }
  } else {
    test("a") {
      ranA.set(true)
    }
  }

  test("b") {
    succeed
  }

  override def run(testName: Option[String], args: Args): Status = {
    val status = super.run(testName, args)
    if (testName.isEmpty) {
      status.whenCompleted(_ => ranA.set(false))
    }
    status
  }
}

object ReplacesARanTestSuite {
  val ranA: AtomicBoolean = new AtomicBoolean
}

@DoNotDiscover
class ReplacesARanTestSuite extends ReplacesARanTest {
  protected def ranA: AtomicBoolean = ReplacesARanTestSuite.ranA
}

object ReplacesARanTestNestingSuitesSuite {
  val ranA: AtomicBoolean = new AtomicBoolean
}

@DoNotDiscover
class ReplacesARanTestNestingSuitesSuite extends ReplacesARanTest {
  protected def ranA: AtomicBoolean = ReplacesARanTestNestingSuitesSuite.ranA

  override val nestedSuites: IndexedSeq[Suite] = Vector(new FirstNestedSuite, new SecondNestedSuite)
}

@DoNotDiscover
class KeepsItsTestsNestingSuitesSuite extends AnyFunSuite with OneInstancePerTest {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }

  override val nestedSuites: IndexedSeq[Suite] = Vector(new FirstNestedSuite, new SecondNestedSuite)
}

object Dropped extends Tag("samples.Dropped")

@DoNotDiscover
class RunsADroppedTestNestingSuitesSuite extends AnyFunSuite {

  test("a", Dropped) {
    succeed
  }

  override val nestedSuites: IndexedSeq[Suite] = Vector(new FirstNestedSuite, new SecondNestedSuite)

  override protected def runTests(testName: Option[String], args: Args): Status = {
    val status = super.runTests(testName, args)
    status.succeeds()
    runTest("a", args)
  }
}

@DoNotDiscover
class FirstNestedSuite extends AnyFunSuite

@DoNotDiscover
class SecondNestedSuite extends AnyFunSuite

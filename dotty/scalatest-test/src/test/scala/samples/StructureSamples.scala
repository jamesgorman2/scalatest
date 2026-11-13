package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.Filter
import org.scalatest.RandomTestOrder
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.Tag
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.refspec.RefSpec
import scala.collection.immutable.ListSet
import scala.jdk.CollectionConverters.*

object Slow extends Tag("samples.Slow")

@DoNotDiscover
class FilteredSuite extends AnyFunSuite {

  test("fast") {
    succeed
  }

  test("slow", Slow) {
    succeed
  }

  ignore("later") {
    succeed
  }
}

@DoNotDiscover
class ShuffledSuite extends AnyFunSuite with RandomTestOrder {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }

  test("c") {
    succeed
  }
}

@DoNotDiscover
class PlainSuite extends Suite {

  override def testNames: Set[String] = ListSet("b", "a", "c")

  override def tags: Map[String, Set[String]] = Map("c" -> Set("samples.Slow"))

  override protected def runTest(testName: String, args: Args): Status = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName))
    args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName, Vector.empty))
    SucceededStatus
  }
}

@DoNotDiscover
class Inner1Suite extends AnyFunSuite {

  test("inner one") {
    succeed
  }
}

@DoNotDiscover
class Inner2Suite extends AnyFunSuite {

  test("inner two") {
    succeed
  }
}

@DoNotDiscover
class OuterSuites extends Suites(new Inner1Suite, new Inner2Suite)

@DoNotDiscover
class BrokenCtorSpec extends AnyFunSpec {

  throw new IllegalStateException("no config")
}

// Its one test passes only where the event log, named by the config map's eventLog, held this suite's structure
// when the suite started to run.
@DoNotDiscover
class ReadsItsStructureSuite extends AnyFunSuite {

  @volatile private var eventLogWhenRunStarted = Vector.empty[String]

  override def run(testName: Option[String], args: Args): Status = {
    eventLogWhenRunStarted = Files.readAllLines(Paths.get(args.configMap.getRequired[String]("eventLog"))).asScala.toVector
    super.run(testName, args)
  }

  test("reads its structure") {
    val structures = eventLogWhenRunStarted.filter(_.contains(""""item":"structure""""))
    assert(structures.size == 1 && structures.head.contains(""""name":"ReadsItsStructureSuite""""))
  }
}

// Registers its scopes and tests when first asked for them, and throws as it does.
@DoNotDiscover
class ThrowingScopeRefSpec extends RefSpec {

  object `a scope` {
    throw new RuntimeException("boom")
    def `t 1`: Unit = ()
  }
}

// Registers scope b when first asked for its tests, in a second instance, whose constructor then throws.
@DoNotDiscover
class ThrowingSecondScopePathFunSpec extends PathAnyFunSpec {

  describe("a") {
    it("t") {}
  }

  describe("b") {
    throw new RuntimeException("boom")
  }
}

// Each counts no test of the suite it nests, so nothing asks that suite for its tests before this suite runs.
@DoNotDiscover
class NestsThrowingScopeRefSpec extends Suites(new ThrowingScopeRefSpec) {
  override def expectedTestCount(filter: Filter): Int = 0
}

@DoNotDiscover
class NestsThrowingSecondScopePathFunSpec extends Suites(new ThrowingSecondScopePathFunSpec) {
  override def expectedTestCount(filter: Filter): Int = 0
}

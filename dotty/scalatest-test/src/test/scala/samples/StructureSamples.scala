package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.RandomTestOrder
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.Tag
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
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

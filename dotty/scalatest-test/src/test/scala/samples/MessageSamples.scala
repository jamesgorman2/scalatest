package samples

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.Alerting
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.Documenting
import org.scalatest.Informing
import org.scalatest.Notifying
import org.scalatest.Reporter
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.events.Event
import org.scalatest.freespec.PathAnyFreeSpec
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

/** When a sample's test called each of its messages: the time before the call and the time after it, by suite class. */
object MessageCallTimes {

  private val bySuiteClass = new ConcurrentHashMap[String, ConcurrentLinkedQueue[(Long, Long)]]

  private def of(suiteClassName: String): ConcurrentLinkedQueue[(Long, Long)] =
    bySuiteClass.computeIfAbsent(suiteClassName, _ => new ConcurrentLinkedQueue[(Long, Long)])

  def clear(suiteClassName: String): Unit = of(suiteClassName).clear()

  def of(suiteClass: Class[?]): Vector[(Long, Long)] = of(suiteClass.getName).asScala.toVector

  def timed(suiteClass: Class[?])(call: => Unit): Unit = {
    val before = System.currentTimeMillis
    call
    of(suiteClass.getName).add(before -> System.currentTimeMillis)
    // Keeps each call's time apart from the next call's and from the test's close.
    Thread.sleep(20)
  }
}

/** A test that calls `info`, `note`, `alert` and `markup` in that order, timing each call. */
trait CallsEachMessage extends Suite { this: Informing with Notifying with Alerting with Documenting =>

  protected def callEachMessage(): Unit = {
    MessageCallTimes.timed(getClass)(info("checked size"))
    MessageCallTimes.timed(getClass)(note("a note"))
    MessageCallTimes.timed(getClass)(alert("an alert"))
    MessageCallTimes.timed(getClass)(markup("*markup*"))
  }

  override def suiteName: String = "MessagesSpec"
}

@DoNotDiscover
class SucceedingMessagesFunSpec extends AnyFunSpec with CallsEachMessage {

  it("is empty") {
    callEachMessage()
  }
}

@DoNotDiscover
class FailingMessagesFunSpec extends AnyFunSpec with CallsEachMessage {

  it("is empty") {
    callEachMessage()
    fail("not empty")
  }
}

@DoNotDiscover
class ForwardingMessagesFunSpec extends AnyFunSpec with CallsEachMessage {

  override protected def runTest(testName: String, args: Args): Status = {
    val forwarding = new Reporter {
      def apply(event: Event): Unit = args.reporter(event)
    }
    super.runTest(testName, args.copy(reporter = forwarding))
  }

  it("is empty") {
    callEachMessage()
  }
}

@DoNotDiscover
class SucceedingMessagesPathFunSpec extends PathAnyFunSpec with CallsEachMessage {

  it("is empty") {
    callEachMessage()
  }
}

@DoNotDiscover
class FailingMessagesPathFunSpec extends PathAnyFunSpec with CallsEachMessage {

  it("is empty") {
    callEachMessage()
    fail("not empty")
  }
}

@DoNotDiscover
class SucceedingMessagesPathFreeSpec extends PathAnyFreeSpec with CallsEachMessage {

  "is empty" in {
    callEachMessage()
  }
}

@DoNotDiscover
class FailingMessagesPathFreeSpec extends PathAnyFreeSpec with CallsEachMessage {

  "is empty" in {
    callEachMessage()
    fail("not empty")
  }
}

/** A test that reports `text` as an info, then fails with it as the failure's message. */
abstract class ReportsText(val text: String) extends AnyFunSuite {

  override def suiteName: String = "TextSuite"

  test("reports") {
    info(text)
    fail(text)
  }
}

@DoNotDiscover
class TwoLinesTextSuite extends ReportsText("first line\nsecond line")

@DoNotDiscover
class NonAsciiTextSuite extends ReportsText("\u00fc \u00df \u65e5\u672c \u2713")

@DoNotDiscover
class LoneSurrogateTextSuite extends ReportsText("a lone surrogate \uD800")

@DoNotDiscover
class ControlCharactersTextSuite extends ReportsText("tab\tand carriage return\r")

@DoNotDiscover
class EmptyTextSuite extends ReportsText("")

/** The throwable `ThrowSuite`'s test threw in its latest run. */
object ThrowSuite {
  val thrown = new AtomicReference[Throwable]
}

@DoNotDiscover
class ThrowSuite extends AnyFunSuite {

  test("throws") {
    val outer = new IllegalStateException("outer", new java.io.IOException("inner"))
    outer.addSuppressed(new RuntimeException("closing"))
    ThrowSuite.thrown.set(outer)
    throw outer
  }
}

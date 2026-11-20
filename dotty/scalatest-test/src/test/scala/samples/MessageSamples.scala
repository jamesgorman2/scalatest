package samples

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

object MessageCallTimes {

  private val current = new AtomicReference(new ConcurrentLinkedQueue[(Long, Long)])

  /**
   * Gives what `launch` gives, and the times of the message calls of the suites constructed while it ran. Each suite
   * records into the recording current when it is constructed, so a run that outlives its recording cannot write into a
   * later one.
   */
  def recordedIn[A](launch: => A): (A, Vector[(Long, Long)]) = synchronized {
    val recording = new ConcurrentLinkedQueue[(Long, Long)]
    current.set(recording)
    val launched = launch
    (launched, recording.asScala.toVector)
  }

  private[samples] def currentRecording: ConcurrentLinkedQueue[(Long, Long)] = current.get
}

trait CallsEachMessage extends Suite { this: Informing with Notifying with Alerting with Documenting =>

  private val callTimes = MessageCallTimes.currentRecording

  protected def callEachMessage(): Unit = {
    timed(info("checked size"))
    timed(note("a note"))
    timed(alert("an alert"))
    timed(markup("*markup*"))
  }

  private def timed(call: => Unit): Unit = {
    val before = System.currentTimeMillis
    call
    callTimes.add(before -> System.currentTimeMillis)
    // Keeps each call's time apart from the next call's and from the test's close.
    Thread.sleep(20)
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

@DoNotDiscover
class ThrowSuite extends AnyFunSuite {

  test("throws") {
    val outer = new IllegalStateException("outer", new java.io.IOException("inner"))
    outer.addSuppressed(new RuntimeException("closing"))
    throw outer
  }
}

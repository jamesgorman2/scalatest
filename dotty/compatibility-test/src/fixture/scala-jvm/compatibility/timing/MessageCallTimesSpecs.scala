package compatibility.timing

import org.scalatest.Informer
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite

/**
 * Sends a message, then a second naming the times read just before and just after the first call, and waits before
 * the test closes, so that the time of the call and the time of the test's closing event differ.
 */
private object TimedMessages {

  def send(info: Informer): Unit = {
    val before = System.currentTimeMillis
    info("a timed message")
    val after = System.currentTimeMillis
    info(s"a timed message was called between $before and $after")
    Thread.sleep(100)
  }
}

class AnyFunSuiteMessageCallTimesSpec extends AnyFunSuite {
  test("sends a timed message") {
    TimedMessages.send(info)
  }
}

class PathAnyFunSpecMessageCallTimesSpec extends PathAnyFunSpec {
  it("sends a timed message") {
    TimedMessages.send(info)
  }
}

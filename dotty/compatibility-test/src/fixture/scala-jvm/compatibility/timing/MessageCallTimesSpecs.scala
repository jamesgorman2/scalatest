package compatibility.timing

import org.scalatest.Informer
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite

private object TimedMessages {

  def send(info: Informer): Unit = {
    val before = System.currentTimeMillis
    info("a timed message")
    val after = System.currentTimeMillis
    info(s"a timed message was called between $before and $after")
    // So that the timed message's call time differs from the time of the test's closing event.
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

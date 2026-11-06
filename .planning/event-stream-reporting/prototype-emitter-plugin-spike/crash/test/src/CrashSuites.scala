// PROTOTYPE - throwaway. Each suite is its own fork group, so each runs in its own worker JVM.
package crash

import org.scalatest.funsuite.AnyFunSuite

/** Two tests pass, then a burst of lines goes to the socket and the JVM halts; the reader keeps up. */
class HaltSuite extends AnyFunSuite {
  test("h1") { Thread.sleep(300) }
  test("h2") { Thread.sleep(300) }
  test("burst then halt") {
    spike.SocketEmitter.burst(20000, "halt")
    Runtime.getRuntime.halt(3)
  }
  test("never runs") {}
}

/** One test passes, then a burst; the plugin kills this JVM from outside while reading it. */
class KillSuite extends AnyFunSuite {
  test("k1") { Thread.sleep(300) }
  test("burst then wait to be killed") {
    spike.SocketEmitter.burst(20000, "ext")
    System.err.println("MARK-ERR KillSuite burst returned, sleeping")
    Thread.sleep(20000)
  }
  test("never runs") {}
}

class CrashOk extends AnyFunSuite {
  for (i <- 1 to 3) test(s"ok$i") { Thread.sleep(300) }
}

/** As HaltSuite, but the plugin pauses reading this connection when the burst starts. */
class HaltLaggingSuite extends AnyFunSuite {
  test("h1") { Thread.sleep(300) }
  test("burst then halt while the reader lags") {
    spike.SocketEmitter.burst(20000, "haltlag")
    System.err.println("MARK-ERR HaltLaggingSuite burst returned, halting")
    Runtime.getRuntime.halt(3)
  }
}

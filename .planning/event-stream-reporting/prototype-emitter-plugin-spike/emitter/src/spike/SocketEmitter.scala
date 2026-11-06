// PROTOTYPE - throwaway. A stub emitter: a ScalaTest Reporter turned on with `-C spike.SocketEmitter`.
// It never connects in its constructor. It holds RunStarting, which ScalaTest fires when the runner is
// constructed (so also in Mill's discovery runner), and connects on the first other event.
// The address and module label reach it as ScalaTest `-D` config-map entries in RunStarting.
// Each event is one tab-separated line, written straight to the socket's unbuffered stream.
package spike

import org.scalatest.Reporter
import org.scalatest.events.*
import java.net.Socket
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable.ArrayBuffer

object SocketEmitter {
  @volatile var current: SocketEmitter = null

  /** Writes `n` lines straight to the socket, logging progress to stderr every 500 lines. */
  def burst(n: Int, tag: String): Unit = {
    val c = current
    if (c == null) System.err.println(s"MARK-NO-EMITTER $tag")
    else c.burst(n, tag)
  }
}

class SocketEmitter extends Reporter {
  SocketEmitter.current = this
  private val runId = java.util.UUID.randomUUID.toString.take(8)
  private val pid = ProcessHandle.current.pid
  private var addr: Option[String] = None
  private var addrFrom = "none"
  private var module = "?"
  private var out: OutputStream = null
  private var closed = false
  private var seq = 0L
  private val held = ArrayBuffer[String]()

  private def clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

  private def line(kind: String, a: String = "", b: String = "", c: String = ""): String = {
    seq += 1
    s"EV\t$runId\t$seq\t${System.currentTimeMillis}\t$kind\t${clean(a)}\t${clean(b)}\t${clean(c)}\n"
  }

  private def connect(): Unit = {
    val a = addr.getOrElse(sys.props.getOrElse("spike.addr", sys.env.getOrElse("SPIKE_ADDR", "")))
    if (addr.isEmpty && a.nonEmpty) addrFrom = "sysprop-or-env"
    val Array(host, port) = a.split(":")
    val s = new Socket(host, port.toInt)
    s.setTcpNoDelay(true)
    out = s.getOutputStream
    val start = ProcessHandle.current.info.startInstant.map(_.toEpochMilli.toString).orElse("?")
    write(s"HELLO\t$runId\t$pid\t$module\t${System.getProperty("user.dir")}\t$start\t$addrFrom\n")
    held.foreach(write)
    held.clear()
  }

  private def write(s: String): Unit = if (!closed) out.write(s.getBytes(UTF_8))

  def apply(e: Event): Unit = synchronized {
    e match {
      case rs: RunStarting =>
        rs.configMap.get("spike.addr").foreach { v => addr = Some(v.toString); addrFrom = "framework-arg -D" }
        rs.configMap.get("spike.module").foreach(v => module = v.toString)
        held += line("RunStarting", rs.testCount.toString)
      case _ =>
        if (out == null) connect()
        val l = e match {
          case x: SuiteStarting  => line("SuiteStarting", x.suiteName)
          case x: SuiteCompleted => line("SuiteCompleted", x.suiteName)
          case x: SuiteAborted   => line("SuiteAborted", x.suiteName, "", x.message)
          case x: TestStarting   => line("TestStarting", x.suiteName, x.testName)
          case x: TestSucceeded  => line("TestSucceeded", x.suiteName, x.testName)
          case x: TestFailed     => line("TestFailed", x.suiteName, x.testName, x.message)
          case x: TestIgnored    => line("TestIgnored", x.suiteName, x.testName)
          case x: TestCanceled   => line("TestCanceled", x.suiteName, x.testName)
          case x: RunCompleted   => line("RunCompleted")
          case x: RunStopped     => line("RunStopped")
          case x: RunAborted     => line("RunAborted", "", "", x.message)
          case x                 => line(x.getClass.getSimpleName)
        }
        write(l)
        e match {
          case _: RunCompleted | _: RunStopped | _: RunAborted => out.close(); closed = true
          case _                                               =>
        }
    }
  }

  def burst(n: Int, tag: String): Unit = synchronized {
    if (out == null) connect()
    for (i <- 1 to n) {
      seq += 1
      write(s"BURST\t$runId\t$seq\t${System.currentTimeMillis}\t$tag\t$i\t$n\t${"x" * 40}\n")
      if (i % 500 == 0 || i == n) System.err.println(s"MARK-WROTE $tag $i of $n seq=$seq")
    }
  }
}

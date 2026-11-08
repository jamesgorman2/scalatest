// PROTOTYPE - throwaway. Ten suites in two fork groups of five, testParallelism on, run with `-j 3`
// so each group's three workers claim several classes from the queue.
//
// Every write a check depends on is marked:
// - SEQ-OUT / SEQ-ERR: a per-JVM sequence number on System.out and System.err, to check order.
// - CHILD-OUT / CHILD-ERR: a child process writing to the inherited descriptors, bypassing System.out.
// - The JVM's own warning for -Xverify:none (forkArgs), written by HotSpot to descriptor 2.
// Each JVM also writes worker-info-<pid>.txt in its working directory: its arguments, input
// arguments, environment and user.dir, for comparison with a run of Mill's own testForked.
// SPIKE_FAIL in the environment makes gamma.G03 t2 fail.
package gamma

import org.scalatest.funsuite.AnyFunSuite

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

object Rec {
  val pid: Long = ProcessHandle.current.pid
  private var outSeq = 0L
  private var errSeq = 0L
  def out(s: String): Unit = synchronized { outSeq += 1; System.out.println(s"SEQ-OUT pid=$pid n=$outSeq $s") }
  def err(s: String): Unit = synchronized { errSeq += 1; System.err.println(s"SEQ-ERR pid=$pid n=$errSeq $s") }

  lazy val info: Unit = {
    val h = ProcessHandle.current.info
    val lines =
      Seq(s"pid\t$pid", s"userDir\t${System.getProperty("user.dir")}") ++
        Seq(s"command\t${h.command.orElse("?")}") ++
        h.arguments.map(a => a.toSeq).orElse(Seq.empty).map(a => s"argument\t$a") ++
        Seq(s"commandLine\t${h.commandLine.orElse("?")}") ++
        ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.map(a => s"inputArgument\t$a") ++
        System.getenv.asScala.toSeq.sorted.map((k, v) => s"env\t$k=$v")
    Files.write(Paths.get(s"worker-info-$pid.txt"), lines.mkString("", "\n", "\n").getBytes(UTF_8))
  }

  def child(tag: String): Unit = {
    val cmd =
      if (System.getProperty("os.name").startsWith("Windows"))
        Seq("cmd.exe", "/c", s"echo CHILD-OUT $tag pid=$pid& echo CHILD-ERR $tag pid=$pid 1>&2")
      else Seq("/bin/sh", "-c", s"echo CHILD-OUT $tag pid=$pid; echo CHILD-ERR $tag pid=$pid >&2")
    new ProcessBuilder(cmd*).inheritIO().start().waitFor()
  }
}

abstract class Base extends AnyFunSuite {
  Rec.info
  private val name = getClass.getSimpleName
  for (i <- 1 to 3) test(s"t$i") {
    if (i == 1) Rec.child(name)
    for (j <- 1 to 10) { Rec.out(s"$name t$i $j"); Rec.err(s"$name t$i $j") }
    Thread.sleep(150)
    if (i == 2 && name == "G03" && System.getenv("SPIKE_FAIL") != null) fail("deliberate failure")
  }
}
class G01 extends Base
class G02 extends Base
class G03 extends Base
class G04 extends Base
class G05 extends Base
class G06 extends Base
class G07 extends Base
class G08 extends Base
class G09 extends Base
class G10 extends Base

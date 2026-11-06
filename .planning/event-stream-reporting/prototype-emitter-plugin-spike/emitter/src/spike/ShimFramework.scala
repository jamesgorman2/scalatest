// PROTOTYPE - throwaway. A shim `testFramework`: when SPIKE_SHIM_CAPTURE is set (only in the worker's
// environment, through forkEnv), `runner` replaces System.out and System.err with files in the
// working directory before delegating to ScalaTest's Framework. Mill's discovery call in its own
// process does not see forkEnv, so it is not replaced there.
package spike

import java.io.{FileOutputStream, PrintStream}

class ShimFramework extends sbt.testing.Framework {
  private val delegate = new org.scalatest.tools.Framework
  def name(): String = delegate.name()
  def fingerprints(): Array[sbt.testing.Fingerprint] = delegate.fingerprints()
  def runner(args: Array[String], remoteArgs: Array[String], cl: ClassLoader): sbt.testing.Runner = {
    if (sys.env.contains("SPIKE_SHIM_CAPTURE")) {
      System.setOut(new PrintStream(new FileOutputStream("shim-stdout.txt"), true))
      System.setErr(new PrintStream(new FileOutputStream("shim-stderr.txt"), true))
      System.out.println(s"MARK-SHIM-INSTALLED pid=${ProcessHandle.current.pid}")
    }
    delegate.runner(args, remoteArgs, cl)
  }
}

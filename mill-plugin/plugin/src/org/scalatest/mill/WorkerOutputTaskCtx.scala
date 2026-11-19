package org.scalatest.mill

import mill.api.Logger
import mill.api.TaskCtx
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.daemon.internal.TestReporter
import scala.concurrent.Future

/**
 * The test command's `TaskCtx`, except that each async body forked through it runs inside [[capturing]], so a worker
 * spawned in that body sends its stdout and stderr to `capture` rather than to Mill's logging. Mill
 * sets `os.Inherit` around each async body it runs, so only a value set inside the body reaches the worker.
 */
private[mill] final class WorkerOutputTaskCtx(ctx: TaskCtx, capture: WorkerOutputCapture) extends TaskCtx {

  /** Runs `body` with the stdout and stderr of each process it spawns sent to `capture`. */
  def capturing[T](body: => T): T =
    os.Inherit.out.withValue(os.ProcessOutput.ReadBytes(capture.stdout)) {
      os.Inherit.err.withValue(os.ProcessOutput.ReadBytes(capture.stderr)) {
        body
      }
    }

  val fork: TaskCtx.Fork.Api = new TaskCtx.Fork.Api {
    def blocking[T](t: => T): T = ctx.fork.blocking(t)

    def await[T](t: Future[T]): T = ctx.fork.await(t)

    def awaitAll[T](t: Seq[Future[T]]): Seq[T] = ctx.fork.awaitAll(t)

    def async[T](dest: os.Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        forkingCtx: TaskCtx
    ): Future[T] =
      ctx.fork.async(dest, key, message, priority)(logger => capturing(t(logger)))(using forkingCtx)
  }

  def args: IndexedSeq[?] = ctx.args
  def arg[T](index: Int): T = ctx.arg(index)
  def dest: os.Path = ctx.dest
  def log: Logger = ctx.log
  def env: Map[String, String] = ctx.env
  def workspace: os.Path = ctx.workspace
  def jobs: Int = ctx.jobs
  def offline: Boolean = ctx.offline
  override def useFileLocks: Boolean = ctx.useFileLocks
  def fail(msg: String): Nothing = ctx.fail(msg)
  def reporter: Int => Option[CompileProblemReporter] = ctx.reporter
  def testReporter: TestReporter = ctx.testReporter
  override def systemExitWithReason(reason: String, exitCode: Int): Nothing = ctx.systemExitWithReason(reason, exitCode)
}

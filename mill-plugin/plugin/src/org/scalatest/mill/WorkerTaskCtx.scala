package org.scalatest.mill

import mill.api.Logger
import mill.api.TaskCtx
import scala.concurrent.Future

private[mill] final class WorkerTaskCtx(ctx: TaskCtx, capture: WorkerOutputCapture, workerStarting: () => Unit) extends DelegatingTaskCtx(ctx) {

  private def capturing[T](body: => T): T =
    os.Inherit.out.withValue(os.ProcessOutput.ReadBytes(capture.stdout)) {
      os.Inherit.err.withValue(os.ProcessOutput.ReadBytes(capture.stderr)) {
        body
      }
    }

  def runOnThisThread[T](body: => T): T = {
    workerStarting()
    capturing(body)
  }

  override val fork: TaskCtx.Fork.Api = new TaskCtx.Fork.Api {
    def blocking[T](t: => T): T = ctx.fork.blocking(t)

    def await[T](t: Future[T]): T = ctx.fork.await(t)

    def awaitAll[T](t: Seq[Future[T]]): Seq[T] = ctx.fork.awaitAll(t)

    def async[T](dest: os.Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        forkingCtx: TaskCtx
    ): Future[T] =
      // Mill sets `os.Inherit` around each async body it runs, so only a value set inside the body reaches the worker.
      ctx.fork.async(dest, key, message, priority)(logger => runOnThisThread(t(logger)))(using forkingCtx)
  }
}

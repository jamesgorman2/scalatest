package org.scalatest.mill

import mill.api.Logger
import mill.api.TaskCtx
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.daemon.internal.TestReporter

private[mill] abstract class DelegatingTaskCtx(ctx: TaskCtx) extends TaskCtx {

  def fork: TaskCtx.Fork.Api = ctx.fork
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

// PROTOTYPE - throwaway. A Logger compiled by the meta-build (mill-build/src/) inside package
// `mill.ntp`, so that Scala lets it implement and call the `private[mill]` members of
// `mill.api.daemon.Logger` and `Logger.Prompt` (core/api/daemon/src/mill/api/daemon/Logger.scala
// lines 83-98, 180-211 at Mill 1.1.10).
//
// Passed as the `log` of the TaskCtx under which Mill's TestModuleUtil runs, it aims to keep the
// runner's progress off the prompt while leaving the goal's own prompt line alone:
// - `ticker` drops the completion ticker (`N/M completed`, TestModuleUtil.waitForFutures);
// - its prompt drops `setPromptLine`, `removePromptLine` and `setPromptDetail` for any key below
//   its own, which is what `fork.async`'s PrefixLogger(ctx.log, Seq(label)) sets for each worker
//   (its `withPromptLine`) and for each worker ticker (`PrefixLogger.ticker`).
// Everything else, including log lines, colours and Chrome profile entries, is delegated.
package mill.ntp

import mill.api.daemon.{Logger, SystemStreams}

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

object QuietLogger {
  val droppedTickers = new AtomicInteger
  val droppedLines = new AtomicInteger
  val droppedDetails = new AtomicInteger
  val passedLogLines = new AtomicInteger
  def counts: String =
    s"tickers=${droppedTickers.get} promptLines=${droppedLines.get} promptDetails=${droppedDetails.get} " +
      s"logLines=${passedLogLines.get}"
  def reset(): Unit = Seq(droppedTickers, droppedLines, droppedDetails, passedLogLines).foreach(_.set(0))
}

final class QuietLogger(real: Logger) extends Logger {
  def streams: SystemStreams = real.streams
  override private[mill] def unprefixedStreams: SystemStreams = real.unprefixedStreams
  def info(s: String): Unit = real.info(s)
  def debug(s: String): Unit = real.debug(s)
  def warn(s: String): Unit = real.warn(s)
  def error(s: String): Unit = real.error(s)
  def ticker(s: String): Unit = QuietLogger.droppedTickers.incrementAndGet()
  override private[mill] def logKey: Seq[String] = real.logKey
  override private[mill] def keySuffix: String = real.keySuffix
  override private[mill] def message: String = real.message
  override private[mill] def redirectOutToErr: Boolean = real.redirectOutToErr
  private val quietPrompt = new QuietPrompt(real.prompt, real.logKey)
  private[mill] def prompt: Logger.Prompt = quietPrompt
  override def toString: String = s"QuietLogger($real)"
}

final class QuietPrompt(real: Logger.Prompt, own: Seq[String]) extends Logger.Prompt {
  private def below(key: Seq[String]): Boolean = key.length > own.length && key.startsWith(own)
  def logLock[T](block: => T): T = real.logLock(block)
  private[mill] def setPromptDetail(key: Seq[String], s: String): Unit =
    if (below(key)) QuietLogger.droppedDetails.incrementAndGet() else real.setPromptDetail(key, s)
  private[mill] def logPrefixedLine(key: Seq[String], logMsg: ByteArrayOutputStream, logToOut: Boolean): Unit = {
    QuietLogger.passedLogLines.incrementAndGet()
    real.logPrefixedLine(key, logMsg, logToOut)
  }
  private[mill] def setPromptLine(key: Seq[String], keySuffix: String, message: String): Unit =
    if (below(key)) QuietLogger.droppedLines.incrementAndGet() else real.setPromptLine(key, keySuffix, message)
  private[mill] def setPromptHeaderPrefix(s: String): Unit = real.setPromptHeaderPrefix(s)
  private[mill] def clearPromptStatuses(): Unit = real.clearPromptStatuses()
  private[mill] def removePromptLine(key: Seq[String], message: String): Unit =
    if (!below(key)) real.removePromptLine(key, message)
  private[mill] def withPromptPaused[T](t: => T): T = real.withPromptPaused(t)
  private[mill] def withPromptUnpaused[T](t: => T): T = real.withPromptUnpaused(t)
  private[mill] def beginChromeProfileEntry(text: String): Unit = real.beginChromeProfileEntry(text)
  private[mill] def endChromeProfileEntry(): Unit = real.endChromeProfileEntry()
  override private[mill] def logBeginChromeProfileEntry(message: String, timestamp: Long): Unit =
    real.logBeginChromeProfileEntry(message, timestamp)
  override private[mill] def logEndChromeProfileEntry(timestamp: Long): Unit = real.logEndChromeProfileEntry(timestamp)
  def debugEnabled: Boolean = real.debugEnabled
  private[mill] def enableTicker: Boolean = real.enableTicker
  def infoColor(s: String): String = real.infoColor(s)
  def warnColor(s: String): String = real.warnColor(s)
  def errorColor(s: String): String = real.errorColor(s)
  def successColor(s: String): String = real.successColor(s)
  override def highlightColor(s: String): String = real.highlightColor(s)
  def colored: Boolean = real.colored
}

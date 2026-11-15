package org.scalatest.mill

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.JvmBuildServer
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PrintParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ScalaBuildServer
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import mill.testkit.IntegrationTester
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** The BSP requests these tests send, as Mill's BSP server serves them. */
trait MillBuildServer extends BuildServer with ScalaBuildServer with JvmBuildServer

/** What a BSP client received for one request: the response and the notifications sent while it was served. */
final case class BspExchange(response: String, notifications: Seq[String]) {

  def describe: String = (s"response: $response" +: notifications.map(notification => s"notification: $notification")).mkString("\n")
}

/**
 * A BSP client connected to Mill's BSP server, started in a tester's workspace as `.bsp/mill-bsp.json` would start it.
 *
 * Each exchange is recorded as JSON, with each duration, timestamp and task ID left out.
 */
final class BspClientSession private (process: os.SubProcess, server: MillBuildServer, client: RecordingBuildClient) {

  /**
   * Sends a request and records its exchange. The response is the result, or the error the server answered with.
   *
   * @return the result, if the server answered with one, and the exchange
   */
  def exchange[A](request: MillBuildServer => CompletableFuture[A]): (Option[A], BspExchange) = {
    client.takeNotifications()
    val response =
      try {
        Right(request(server).get(BspClientSession.requestTimeoutMinutes, TimeUnit.MINUTES))
      } catch {
        case failure: ExecutionException if failure.getCause.isInstanceOf[ResponseErrorException] =>
          Left(failure.getCause.asInstanceOf[ResponseErrorException].getResponseError)
      }
    val recorded = response.fold(error => s"error ${BspJson.normalised(error)}", result => BspJson.normalised(result))
    (response.toOption, BspExchange(recorded, client.takeNotifications()))
  }

  private def shutdown(): Unit = {
    server.buildShutdown().get(BspClientSession.requestTimeoutMinutes, TimeUnit.MINUTES)
    server.onBuildExit()
    if (!process.waitFor(BspClientSession.exitTimeoutMillis)) {
      process.destroy()
    }
  }
}

object BspClientSession {

  private val requestTimeoutMinutes: Long = 10
  private val exitTimeoutMillis: Long = 30000

  /** Starts Mill's BSP server in the tester's workspace, initializes it, runs `use`, then shuts the server down. */
  def withSession[A](tester: IntegrationTester, millExecutable: os.Path, stderrLog: os.Path)(use: BspClientSession => A): A = {
    val process = os.spawn(
      cmd = (millExecutable, "--bsp", "--jobs", "1"),
      cwd = tester.workspacePath,
      env = tester.millTestSuiteEnv,
      stderr = stderrLog
    )
    try {
      val client = new RecordingBuildClient
      val launcher = new Launcher.Builder[MillBuildServer]()
        .setRemoteInterface(classOf[MillBuildServer])
        .setInput(process.stdout)
        .setOutput(process.stdin)
        .setLocalService(client)
        .create()
      launcher.startListening()
      val server = launcher.getRemoteProxy
      val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
      val initialize = new InitializeBuildParams("scalatest-integration", "1.0", "2.2.0", tester.workspacePath.toNIO.toUri.toString, capabilities)
      server.buildInitialize(initialize).get(requestTimeoutMinutes, TimeUnit.MINUTES)
      server.onBuildInitialized()
      val session = new BspClientSession(process, server, client)
      try {
        use(session)
      } finally {
        session.shutdown()
      }
    } finally {
      if (process.isAlive()) {
        process.destroy()
      }
    }
  }

  def targetsNamed(displayName: String, targets: Seq[ch.epfl.scala.bsp4j.BuildTarget]): java.util.List[BuildTargetIdentifier] =
    targets.filter(_.getDisplayName == displayName).map(_.getId).asJava
}

private final class RecordingBuildClient extends BuildClient {

  private val notifications = mutable.ArrayBuffer.empty[String]

  def takeNotifications(): Seq[String] =
    synchronized {
      val taken = notifications.toVector
      notifications.clear()
      taken
    }

  private def record(method: String, params: Any): Unit = synchronized(notifications += s"$method ${BspJson.normalised(params)}")

  def onBuildShowMessage(params: ShowMessageParams): Unit = record("build/showMessage", params)

  def onBuildLogMessage(params: LogMessageParams): Unit = record("build/logMessage", params)

  def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = record("build/publishDiagnostics", params)

  def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = record("buildTarget/didChange", params)

  def onBuildTaskStart(params: TaskStartParams): Unit = record("build/taskStart", params)

  def onBuildTaskProgress(params: TaskProgressParams): Unit = record("build/taskProgress", params)

  def onBuildTaskFinish(params: TaskFinishParams): Unit = record("build/taskFinish", params)

  def onRunPrintStdout(params: PrintParams): Unit = record("run/printStdout", params)

  def onRunPrintStderr(params: PrintParams): Unit = record("run/printStderr", params)
}

/** BSP messages as JSON, without the durations, timestamps and task IDs that differ between two identical requests. */
private object BspJson {

  private val gson = new Gson

  private val keysLeftOut: Set[String] = Set("eventTime", "taskId", "time")

  def normalised(message: Any): String = withoutValuesNotCompared(gson.toJsonTree(message)).toString

  private def withoutValuesNotCompared(element: JsonElement): JsonElement =
    element match {
      case obj: JsonObject =>
        val kept = new JsonObject
        obj.entrySet.asScala.filterNot(entry => keysLeftOut(entry.getKey)).foreach { entry =>
          kept.add(entry.getKey, withoutValuesNotCompared(entry.getValue))
        }
        kept
      case array: JsonArray =>
        val kept = new JsonArray
        array.asScala.foreach(item => kept.add(withoutValuesNotCompared(item)))
        kept
      case primitive: JsonPrimitive if primitive.isString => new JsonPrimitive(Durations.replaced(primitive.getAsString))
      case other => other
    }
}

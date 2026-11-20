package org.scalatest.mill

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.JvmBuildServer
import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PrintParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ScalaBuildServer
import ch.epfl.scala.bsp4j.ScalaTestClassesParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TestParams
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
import scala.annotation.nowarn
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.Using.Releasable

private trait MillBuildServer extends BuildServer with ScalaBuildServer with JvmBuildServer

final case class BspSessionRecord(responses: Map[String, String], notifications: Seq[String]) {

  def describe: String =
    (responses.toSeq.map((request, response) => s"response to $request: $response") ++
      notifications.map(notification => s"notification: $notification")).mkString("\n")
}

final case class BspResponse[A](result: Option[A], recorded: String) {

  def map[B](f: A => B): BspResponse[B] = BspResponse(result.map(f), recorded)
}

final case class WithNotifications[A](result: A, notifications: Seq[String])

final class BspClientSession private (server: MillBuildServer) {

  import BspClientSession.BuildTargets

  def buildTargets(): BspResponse[BuildTargets] =
    exchange(server.workspaceBuildTargets()).map(result => new BuildTargets(result.getTargets.asScala.toSeq))

  // BSP deprecates buildTarget/scalaTestClasses, but Metals and IntelliJ still send it, and Mill still serves it.
  @nowarn("cat=deprecation")
  def scalaTestClasses(targets: BuildTargets): String =
    exchange(server.buildTargetScalaTestClasses(new ScalaTestClassesParams(targets.identifiers))).recorded

  def jvmTestEnvironment(targets: BuildTargets): String =
    exchange(server.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(targets.identifiers))).recorded

  def test(targets: BuildTargets): String = exchange(server.buildTargetTest(new TestParams(targets.identifiers))).recorded

  private def exchange[A](request: CompletableFuture[A]): BspResponse[A] = {
    val response =
      try {
        Right(request.get(BspClientSession.requestTimeoutMinutes, TimeUnit.MINUTES))
      } catch {
        case failure: ExecutionException if failure.getCause.isInstanceOf[ResponseErrorException] =>
          Left(failure.getCause.asInstanceOf[ResponseErrorException].getResponseError)
      }
    BspResponse(response.toOption, response.fold(error => s"error ${BspJson.normalised(error)}", result => BspJson.normalised(result)))
  }

  private def shutDown(): Unit = {
    server.buildShutdown().get(BspClientSession.requestTimeoutMinutes, TimeUnit.MINUTES)
    server.onBuildExit()
  }
}

object BspClientSession {

  private val requestTimeoutMinutes: Long = 10
  private val exitTimeoutMillis: Long = 30000

  final class BuildTargets private[BspClientSession] (targets: Seq[BuildTarget]) {

    def named(displayName: String): BuildTargets = new BuildTargets(targets.filter(_.getDisplayName == displayName))

    def isEmpty: Boolean = targets.isEmpty

    private[BspClientSession] def identifiers: java.util.List[BuildTargetIdentifier] = targets.map(_.getId).asJava
  }

  private given shutsDownTheServer: Releasable[BspClientSession] = session => session.shutDown()

  private given destroysTheServer: Releasable[os.SubProcess] = process =>
    if (process.isAlive()) {
      process.destroy()
      require(process.waitFor(exitTimeoutMillis), s"Mill's BSP server did not exit within $exitTimeoutMillis ms of being destroyed")
    }

  /**
   * Starts the server as `.bsp/mill-bsp.json` would. Once `use` returns, shuts the server down and waits for it to close
   * the connection, so that every notification it sent has been received.
   */
  def resultWithNotifications[A](tester: IntegrationTester, millExecutable: os.Path, stderrLog: os.Path)(
    use: BspClientSession => A
  ): WithNotifications[A] = {
    val spawned = os.spawn(
      cmd = (millExecutable, "--bsp", "--jobs", "1"),
      cwd = tester.workspacePath,
      env = tester.millTestSuiteEnv,
      stderr = stderrLog
    )
    Using.resource(spawned) { process =>
      val client = new RecordingBuildClient
      val launcher = new Launcher.Builder[MillBuildServer]()
        .setRemoteInterface(classOf[MillBuildServer])
        .setInput(process.stdout)
        .setOutput(process.stdin)
        .setLocalService(client)
        .create()
      val listening = launcher.startListening()
      val server = launcher.getRemoteProxy
      val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
      val initialize = new InitializeBuildParams("scalatest-integration", "1.0", "2.2.0", tester.workspacePath.toNIO.toUri.toString, capabilities)
      server.buildInitialize(initialize).get(requestTimeoutMinutes, TimeUnit.MINUTES)
      server.onBuildInitialized()
      val result = Using.resource(new BspClientSession(server))(use)
      require(process.waitFor(exitTimeoutMillis), s"Mill's BSP server did not exit within $exitTimeoutMillis ms of build/exit")
      listening.get(exitTimeoutMillis, TimeUnit.MILLISECONDS)
      WithNotifications(result, client.notifications)
    }
  }
}

private final class RecordingBuildClient extends BuildClient {

  private val received = mutable.ArrayBuffer.empty[String]

  def notifications: Seq[String] = synchronized(received.toVector)

  private def record(method: String, params: Any): Unit = synchronized(received += s"$method ${BspJson.normalised(params)}")

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

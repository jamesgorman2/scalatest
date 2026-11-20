package org.scalatest.compatibility

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

final case class PlatformRunOutput(exitCode: Int, stdout: Seq[String], stderr: Seq[String])

/**
 * Runs the fixture's program for Scala.js or Native, once as linked with ScalaTest 3.2.20 and once as linked with this
 * fork, each in a process of its own. A program for Scala.js is run by `node`.
 */
final class PlatformRuns(launcher: Seq[String], programs: Compared[Path], runsDirectory: Path) {

  private val runCount = new AtomicInteger

  def ofFixture(runnerArguments: Seq[String]): Compared[PlatformRunOutput] = {
    val launchArguments = CompatibilityFixture.suiteClassNamesOnEveryRuntime.mkString(",") +: runnerArguments
    Compared(
      run(programs.upstream, "3.2.20", launchArguments),
      run(programs.fork, "fork", launchArguments)
    )
  }

  private def run(program: Path, label: String, launchArguments: Seq[String]): PlatformRunOutput = {
    val directory = Files.createDirectories(runsDirectory.resolve(s"${runCount.incrementAndGet()}-$label"))
    val stdout = directory.resolve("stdout.txt")
    val stderr = directory.resolve("stderr.txt")
    val command = (launcher :+ program.toString) ++ launchArguments
    val process = new ProcessBuilder(command.asJava)
      .directory(directory.toFile)
      .redirectOutput(stdout.toFile)
      .redirectError(stderr.toFile)
      .start()
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      throw new IllegalStateException(s"$program did not end within 2 minutes, in $directory")
    }
    PlatformRunOutput(process.exitValue, lines(stdout), lines(stderr))
  }

  private def lines(file: Path): Seq[String] =
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8).linesIterator.toList
}

object PlatformRuns {
  def load(): PlatformRuns = {
    val properties = new Properties
    val resource = getClass.getResourceAsStream("/compatibility-platform.properties")
    try properties.load(resource) finally resource.close()
    new PlatformRuns(
      properties.getProperty("launcher").split(' ').filter(_.nonEmpty).toList,
      Compared(Paths.get(properties.getProperty("upstreamProgram")), Paths.get(properties.getProperty("forkProgram"))),
      Paths.get(properties.getProperty("runsDirectory"))
    )
  }
}

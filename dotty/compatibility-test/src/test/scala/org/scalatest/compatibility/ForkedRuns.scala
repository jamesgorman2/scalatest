package org.scalatest.compatibility

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

enum Library(val label: String) {
  case Upstream extends Library("3.2.20")
  case Fork extends Library("fork")
}

enum Launch(val mainClass: String) {
  case RunnerRun extends Launch("compatibility.recorders.RunnerLauncher")
  case Framework extends Launch("compatibility.recorders.FrameworkLauncher")
}

final case class Compared[A](upstream: A, fork: A) {
  def map[B](f: A => B): Compared[B] = Compared(f(upstream), f(fork))
}

object Compared {
  def firstDifference(lines: Compared[Seq[String]]): Option[String] =
    lines.upstream.map(Option(_)).zipAll(lines.fork.map(Option(_)), None, None).zipWithIndex.collectFirst {
      case ((upstreamLine, forkLine), index) if upstreamLine != forkLine =>
        s"line ${index + 1}: 3.2.20 wrote ${described(upstreamLine)}, the fork wrote ${described(forkLine)}"
    }

  private def described(line: Option[String]): String = line.fold("no line")(text => s"[$text]")
}

final case class RunOutput(
  events: Seq[String],
  loggerLines: Seq[String],
  summary: Seq[String],
  stdout: String,
  stderr: String,
  files: Map[String, Seq[String]],
  threadNames: Set[String]
) {
  // The fixture's own line is removed as text, not as a line: a reporter may write an event over several writes, and
  // the fixture's line can land between two of them.
  def reporterText: Map[String, Seq[String]] =
    Map(
      "stdout" -> RunOutput.withoutFirst(CompatibilityFixture.stdoutLine, stdout).linesIterator.toList,
      "stderr" -> RunOutput.withoutFirst(CompatibilityFixture.stderrLine, stderr).linesIterator.toList,
      "loggers" -> loggerLines
    ) ++ files.map((name, lines) => ("file " + name) -> lines)
}

object RunOutput {

  /** How many times a line, with its line separator, is written within the text, wherever it begins. */
  def timesWritten(line: String, text: String): Int = {
    val written = line + System.lineSeparator
    var count = 0
    var from = text.indexOf(written)
    while (from >= 0) {
      count += 1
      from = text.indexOf(written, from + written.length)
    }
    count
  }

  private def withoutFirst(line: String, text: String): String = {
    val written = line + System.lineSeparator
    val at = text.indexOf(written)
    if (at < 0) text else text.substring(0, at) + text.substring(at + written.length)
  }
}

final case class ComparisonClasspaths(fixture: String, upstream: String, fork: String, testInterface: String, runsDirectory: Path) {
  def of(library: Library): String = {
    val libraryClasspath = library match {
      case Library.Upstream => upstream
      case Library.Fork => fork
    }
    List(fixture, libraryClasspath, testInterface).mkString(File.pathSeparator)
  }
}

object ComparisonClasspaths {
  def load(): ComparisonClasspaths = {
    val properties = new Properties
    val resource = getClass.getResourceAsStream("/compatibility.properties")
    try properties.load(resource) finally resource.close()
    ComparisonClasspaths(
      properties.getProperty("fixture"),
      properties.getProperty("upstream"),
      properties.getProperty("fork"),
      properties.getProperty("testInterface"),
      Paths.get(properties.getProperty("runsDirectory"))
    )
  }
}

object CompatibilityFixture {
  val stdoutLine: String = "a line on stdout"
  val stderrLine: String = "a line on stderr"

  val suiteClassNames: Seq[String] =
    List(
      "AnyFunSuiteSpec",
      "AsyncFunSuiteSpec",
      "FixtureAnyFunSuiteSpec",
      "FixtureAsyncFunSuiteSpec",
      "AnyPropSpecSpec",
      "FixtureAnyPropSpecSpec",
      "AnyFunSpecSpec",
      "AsyncFunSpecSpec",
      "FixtureAnyFunSpecSpec",
      "FixtureAsyncFunSpecSpec",
      "PathAnyFunSpecSpec",
      "AnyFreeSpecSpec",
      "AsyncFreeSpecSpec",
      "FixtureAnyFreeSpecSpec",
      "FixtureAsyncFreeSpecSpec",
      "PathAnyFreeSpecSpec",
      "AnyWordSpecSpec",
      "AsyncWordSpecSpec",
      "FixtureAnyWordSpecSpec",
      "FixtureAsyncWordSpecSpec",
      "AnyFlatSpecSpec",
      "AsyncFlatSpecSpec",
      "FixtureAnyFlatSpecSpec",
      "FixtureAsyncFlatSpecSpec",
      "AnyFeatureSpecSpec",
      "AsyncFeatureSpecSpec",
      "FixtureAnyFeatureSpecSpec",
      "FixtureAsyncFeatureSpecSpec",
      "RefSpecSpec",
      "NestingSpec",
      "BeforeAllThrowsSpec",
      "ConfigMapWrappedSpec"
    ).map("compatibility.fixture." + _)
}

final class ForkedRuns(classpaths: ComparisonClasspaths) {

  private val runCount = new AtomicInteger

  def ofFixture(launch: Launch, arguments: Seq[String], forkOnlyArguments: Seq[String] = Nil): Compared[RunOutput] =
    Compared(
      ofFixtureOn(Library.Upstream, launch, arguments),
      ofFixtureOn(Library.Fork, launch, arguments ++ forkOnlyArguments)
    )

  def ofEventClassShapes(): Compared[Seq[String]] = {
    def shapesOn(library: Library): Seq[String] = {
      val directory = newRunDirectory("event-classes", library)
      val shapes = directory.resolve("event-classes.txt")
      run(library, directory, List("-Dcompatibility.eventClasses=" + shapes), "compatibility.recorders.EventClassShapes", Nil)
      lines(shapes)
    }
    Compared(shapesOn(Library.Upstream), shapesOn(Library.Fork))
  }

  private def ofFixtureOn(library: Library, launch: Launch, arguments: Seq[String]): RunOutput = {
    val directory = newRunDirectory(launch.toString, library)
    val events = directory.resolve("events.txt")
    val loggerLines = directory.resolve("logger-lines.txt")
    val summary = directory.resolve("summary.txt")
    val threads = directory.resolve("threads.txt")
    val recorderFiles =
      List(
        "-Dcompatibility.events=" + events,
        "-Dcompatibility.loggerLines=" + loggerLines,
        "-Dcompatibility.summary=" + summary,
        "-Dcompatibility.threads=" + threads
      )
    val (suiteOptions, suiteArguments) = launch match {
      case Launch.RunnerRun => (Nil, CompatibilityFixture.suiteClassNames.flatMap(List("-s", _)))
      case Launch.Framework => (List("-Dcompatibility.suites=" + CompatibilityFixture.suiteClassNames.mkString(",")), Nil)
    }
    val workingDirectory = run(library, directory, recorderFiles ++ suiteOptions, launch.mainClass, suiteArguments ++ arguments)
    RunOutput(
      lines(events),
      lines(loggerLines),
      lines(summary),
      text(directory.resolve("stdout.txt")),
      text(directory.resolve("stderr.txt")),
      filesIn(workingDirectory).map(file => file.getFileName.toString -> lines(file)).toMap,
      lines(threads).toSet
    )
  }

  private def run(library: Library, directory: Path, jvmOptions: Seq[String], mainClass: String, arguments: Seq[String]): Path = {
    val workingDirectory = Files.createDirectories(directory.resolve("working-directory"))
    val stderr = directory.resolve("stderr.txt")
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val encodings = List("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val command = List(java, "-cp", classpaths.of(library)) ++ encodings ++ jvmOptions ++ List(mainClass) ++ arguments
    val process = new ProcessBuilder(command.asJava)
      .directory(workingDirectory.toFile)
      .redirectOutput(directory.resolve("stdout.txt").toFile)
      .redirectError(stderr.toFile)
      .start()
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      throw new IllegalStateException(s"$mainClass on ${library.label} did not end within 2 minutes, in $directory")
    }
    if (process.exitValue != 0) {
      throw new IllegalStateException(s"$mainClass on ${library.label} exited with ${process.exitValue}:\n${lines(stderr).mkString("\n")}")
    }
    workingDirectory
  }

  private def newRunDirectory(name: String, library: Library): Path = {
    val directory = classpaths.runsDirectory.resolve(s"${runCount.incrementAndGet()}-$name-${library.label}")
    if (Files.exists(directory)) {
      filesUnder(directory).foreach(Files.delete)
    }
    Files.createDirectories(directory)
  }

  private def filesUnder(directory: Path): Seq[Path] = {
    val walk = Files.walk(directory)
    try walk.sorted(Comparator.reverseOrder[Path]()).iterator.asScala.toList finally walk.close()
  }

  private def filesIn(directory: Path): Seq[Path] = {
    val entries = Files.list(directory)
    try entries.iterator.asScala.toList finally entries.close()
  }

  private def lines(file: Path): Seq[String] = text(file).linesIterator.toList

  private def text(file: Path): String =
    if (Files.exists(file)) {
      new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    } else {
      ""
    }
}

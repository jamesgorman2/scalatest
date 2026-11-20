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
import org.scalatest.tools.TestEventLogArgument
import scala.jdk.CollectionConverters.*

enum Library(val label: String) {
  case Upstream extends Library("3.2.20")
  case Fork extends Library("fork")
}

/** @param named how `compatibility.recorders.ArgumentsLauncher` names the launch */
enum Launch(val mainClass: String, val named: String, val described: String) {
  case RunnerRun extends Launch("compatibility.recorders.RunnerLauncher", "Runner.run", "Runner.run")
  case Framework extends Launch("compatibility.recorders.FrameworkLauncher", "Framework", "a build tool")
}

/** Whether the fork's run is given the runner argument, which turns its emitter on. 3.2.20's run never is. */
enum Emitter {
  case Off
  case On
}

final case class RunOutput(
  events: Seq[String],
  loggerLines: Seq[String],
  summary: Seq[String],
  stdout: String,
  stderr: String,
  files: Map[String, Seq[String]],
  threadNames: Set[String],
  eventFields: Seq[String],
  eventLog: Seq[String]
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

final case class ArgumentsOutcome(exitCode: Int, launchResult: Seq[String], stdout: String, stderr: String)

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

final class ForkedRuns(classpaths: ComparisonClasspaths) {

  private val runCount = new AtomicInteger

  def ofFixture(launch: Launch, arguments: Seq[String], emitter: Emitter = Emitter.Off): Compared[RunOutput] =
    Compared(
      ofFixtureOn(Library.Upstream, launch, arguments, Emitter.Off, CompatibilityFixture.suiteClassNames),
      ofFixtureOn(Library.Fork, launch, arguments, emitter, CompatibilityFixture.suiteClassNames)
    )

  def ofFixtureOnFork(launch: Launch, arguments: Seq[String], emitter: Emitter, moreSuiteClassNames: Seq[String] = Nil): RunOutput =
    ofFixtureOn(Library.Fork, launch, arguments, emitter, CompatibilityFixture.suiteClassNames ++ moreSuiteClassNames)

  /** Gives the argument list to the launch, without running any suite. */
  def ofArguments(launch: Launch, arguments: Seq[String]): Compared[ArgumentsOutcome] =
    ofArgumentsNamingAFile(launch, _ => arguments)

  /** Gives the launch the argument list that `arguments` builds from a file path in the run's own directory, without running any suite. */
  def ofArgumentsNamingAFile(launch: Launch, arguments: Path => Seq[String]): Compared[ArgumentsOutcome] =
    ofArgumentsOnEach(launch, (_, file) => arguments(file))

  /** Gives 3.2.20's launch the argument list, and the fork's the runner argument, naming a file in the run's own directory, followed by the list. */
  def ofArgumentsGivingTheForkTheRunnerArgument(launch: Launch, arguments: Seq[String]): Compared[ArgumentsOutcome] =
    ofArgumentsOnEach(
      launch,
      (library, file) =>
        library match {
          case Library.Upstream => arguments
          case Library.Fork => TestEventLogArgument.forFile(file.toString) ++ arguments
        }
    )

  private def ofArgumentsOnEach(launch: Launch, arguments: (Library, Path) => Seq[String]): Compared[ArgumentsOutcome] = {
    def outcomeOn(library: Library): ArgumentsOutcome = {
      val directory = newRunDirectory("arguments", library)
      val outcome = directory.resolve("outcome.txt")
      val exitCode = runJvm(
        library,
        directory,
        List("-Dcompatibility.outcome=" + outcome, "-Dcompatibility.events=" + directory.resolve("events.txt")),
        "compatibility.recorders.ArgumentsLauncher",
        launch.named +: arguments(library, directory.resolve("file-named"))
      )
      ArgumentsOutcome(exitCode, lines(outcome), text(directory.resolve("stdout.txt")), text(directory.resolve("stderr.txt")))
    }
    Compared(outcomeOn(Library.Upstream), outcomeOn(Library.Fork))
  }

  def ofEventClassShapes(): Compared[Seq[String]] = {
    def shapesOn(library: Library): Seq[String] = {
      val directory = newRunDirectory("event-classes", library)
      val shapes = directory.resolve("event-classes.txt")
      run(library, directory, List("-Dcompatibility.eventClasses=" + shapes), "compatibility.recorders.EventClassShapes", Nil)
      lines(shapes)
    }
    Compared(shapesOn(Library.Upstream), shapesOn(Library.Fork))
  }

  private def ofFixtureOn(library: Library, launch: Launch, arguments: Seq[String], emitter: Emitter, suiteClassNames: Seq[String]): RunOutput = {
    val directory = newRunDirectory(launch.toString, library)
    val events = directory.resolve("events.txt")
    val eventFields = directory.resolve("event-fields.txt")
    val eventLog = directory.resolve("test-event-log.jsonl")
    val runnerArgument = emitter match {
      case Emitter.Off => Nil
      case Emitter.On => TestEventLogArgument.forFile(eventLog.toString)
    }
    val loggerLines = directory.resolve("logger-lines.txt")
    val summary = directory.resolve("summary.txt")
    val threads = directory.resolve("threads.txt")
    val recorderFiles =
      List(
        "-Dcompatibility.events=" + events,
        "-Dcompatibility.eventFields=" + eventFields,
        "-Dcompatibility.loggerLines=" + loggerLines,
        "-Dcompatibility.summary=" + summary,
        "-Dcompatibility.threads=" + threads
      )
    val (suiteOptions, suiteArguments) = launch match {
      case Launch.RunnerRun => (Nil, suiteClassNames.flatMap(List("-s", _)))
      case Launch.Framework => (List("-Dcompatibility.suites=" + suiteClassNames.mkString(",")), Nil)
    }
    val workingDirectory = run(library, directory, recorderFiles ++ suiteOptions, launch.mainClass, suiteArguments ++ arguments ++ runnerArgument)
    RunOutput(
      lines(events),
      lines(loggerLines),
      lines(summary),
      text(directory.resolve("stdout.txt")),
      text(directory.resolve("stderr.txt")),
      filesIn(workingDirectory).map(file => file.getFileName.toString -> lines(file)).toMap,
      lines(threads).toSet,
      lines(eventFields),
      lines(eventLog)
    )
  }

  private def run(library: Library, directory: Path, jvmOptions: Seq[String], mainClass: String, arguments: Seq[String]): Path = {
    val exitCode = runJvm(library, directory, jvmOptions, mainClass, arguments)
    if (exitCode != 0) {
      throw new IllegalStateException(s"$mainClass on ${library.label} exited with $exitCode:\n${lines(directory.resolve("stderr.txt")).mkString("\n")}")
    }
    workingDirectoryOf(directory)
  }

  private def workingDirectoryOf(directory: Path): Path = directory.resolve("working-directory")

  private def runJvm(library: Library, directory: Path, jvmOptions: Seq[String], mainClass: String, arguments: Seq[String]): Int = {
    val workingDirectory = Files.createDirectories(workingDirectoryOf(directory))
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val encodings = List("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val command = List(java, "-cp", classpaths.of(library)) ++ encodings ++ jvmOptions ++ List(mainClass) ++ arguments
    val process = new ProcessBuilder(command.asJava)
      .directory(workingDirectory.toFile)
      .redirectOutput(directory.resolve("stdout.txt").toFile)
      .redirectError(directory.resolve("stderr.txt").toFile)
      .start()
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      throw new IllegalStateException(s"$mainClass on ${library.label} did not end within 2 minutes, in $directory")
    }
    process.exitValue
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

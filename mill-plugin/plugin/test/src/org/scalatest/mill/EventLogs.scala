package org.scalatest.mill

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.RunFeed

private[mill] object EventLogs {

  val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  def withEventLogFile[A](test: Path => A): A =
    TemporaryFolder.within("event-log")(folder => test(folder.resolve(JvmTestModuleRun.eventLogFileName)))

  def append(file: Path, text: String): Unit =
    Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  def appendCompletedRun(file: Path): Unit = append(file, s"$runStarted\n$runEndedCompleted\n")

  def sharingViolation(file: Path): FileNotFoundException =
    new FileNotFoundException(s"$file (The process cannot access the file because it is being used by another process)")

  def openingAfterFailures(file: Path, failures: Int): EventLogFile = {
    val failed = new AtomicLong(0)
    val onDisk = EventLogFile.at(file)
    new EventLogFile {
      def name: String = onDisk.name

      def exists: Boolean = onDisk.exists

      def open(): EventLogInput =
        if (failed.getAndIncrement() < failures) {
          throw sharingViolation(file)
        } else {
          onDisk.open()
        }
    }
  }

  def neverOpening(file: Path): EventLogFile = openingAfterFailures(file, Int.MaxValue)

  final class CountingConsolidation(consolidation: Consolidation, module: ModuleName) {

    private val runCount: AtomicInteger = new AtomicInteger(0)
    private val processEndedBeforeAnyRunCount: AtomicInteger = new AtomicInteger(0)

    def runsAdded: Int = runCount.get

    def processesEndedBeforeAnyRun: Int = processEndedBeforeAnyRunCount.get

    def addRun(readRun: RunFeed => Unit): Unit = {
      runCount.incrementAndGet()
      consolidation.addRun(module)(readRun)
    }

    def addProcessEndedBeforeAnyRun(): Unit = {
      processEndedBeforeAnyRunCount.incrementAndGet()
      consolidation.addProcessEndedBeforeAnyRun()
    }
  }
}

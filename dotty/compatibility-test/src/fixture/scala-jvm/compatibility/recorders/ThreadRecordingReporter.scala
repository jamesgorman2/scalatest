package compatibility.recorders

import org.scalatest.Reporter
import org.scalatest.events.Event
import scala.jdk.CollectionConverters._

/**
 * Records the name of each thread the run's code has started that is alive when an event arrives, once each, with
 * every number in a name replaced by `N`. The JVM's own threads, which are in other thread groups, are left out.
 *
 * `Suite.run` renames the thread that runs a suite, for the length of that run, to its name followed by
 * `-running-<suite name>`, after adding `-ScalaTest` to a name that does not hold `ScalaTest-`. Which of those names
 * an event's arrival finds depends on scheduling, so each is recorded as the name the thread had before.
 */
class ThreadRecordingReporter extends Reporter {

  private val threads = LineFile.namedBy("compatibility.threads")
  private var recorded = Set.empty[String]

  def apply(event: Event): Unit =
    synchronized {
      val alive = Thread.getAllStackTraces.keySet.asScala.toList
        .filter(thread => Option(thread.getThreadGroup).exists(_.getName == "main"))
        .map(_.getName.replaceAll("(-ScalaTest)?-running-.*$", "").replaceAll("[0-9]+", "N"))
      val unrecorded = alive.distinct.filterNot(recorded.contains).sorted
      unrecorded.foreach(threads.append)
      recorded ++= unrecorded
    }
}

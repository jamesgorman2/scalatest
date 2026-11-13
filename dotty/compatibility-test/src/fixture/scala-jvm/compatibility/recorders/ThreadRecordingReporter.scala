package compatibility.recorders

import org.scalatest.Reporter
import org.scalatest.events.Event
import scala.jdk.CollectionConverters._

/**
 * Records the name of each thread the run's code has started that is alive when an event arrives, once each, with
 * every number in a name replaced by `N`. The JVM's own threads, which are in other thread groups, are left out.
 */
class ThreadRecordingReporter extends Reporter {

  private val threads = LineFile.namedBy("compatibility.threads")
  private var recorded = Set.empty[String]

  def apply(event: Event): Unit =
    synchronized {
      val alive = Thread.getAllStackTraces.keySet.asScala.toList
        .filter(thread => Option(thread.getThreadGroup).exists(_.getName == "main"))
        .map(_.getName.replaceAll("[0-9]+", "N"))
      val unrecorded = alive.distinct.filterNot(recorded.contains).sorted
      unrecorded.foreach(threads.append)
      recorded ++= unrecorded
    }
}

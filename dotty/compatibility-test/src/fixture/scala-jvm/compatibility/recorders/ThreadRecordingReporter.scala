package compatibility.recorders

import org.scalatest.Reporter
import org.scalatest.events.Event
import scala.jdk.CollectionConverters._

class ThreadRecordingReporter extends Reporter {

  private val threads = LineFile.namedBy("compatibility.threads")
  private var recorded = Set.empty[String]

  def apply(event: Event): Unit =
    synchronized {
      val alive = Thread.getAllStackTraces.keySet.asScala.toList
        // The JVM's own threads are in other thread groups.
        .filter(thread => Option(thread.getThreadGroup).exists(_.getName == "main"))
        // Suite.run renames a suite's thread while it runs, adding -ScalaTest to a name without ScalaTest-, then
        // -running-<suite name>. Which name an event finds depends on scheduling, so the name the thread had before
        // is recorded.
        .map(_.getName.replaceAll("(-ScalaTest)?-running-.*$", "").replaceAll("[0-9]+", "N"))
      val unrecorded = alive.distinct.filterNot(recorded.contains).sorted
      unrecorded.foreach(threads.append)
      recorded ++= unrecorded
    }
}

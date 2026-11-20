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
        // Suite.run renames a suite's thread for the run: it appends -ScalaTest unless the name holds ScalaTest-, then
        // -running-<suite name>. An event can arrive before or after the rename,
        // so the name is recorded without either suffix.
        .map(_.getName.replaceAll("(-ScalaTest)?-running-.*$", "").replaceAll("[0-9]+", "N"))
      val unrecorded = alive.distinct.filterNot(recorded.contains).sorted
      unrecorded.foreach(threads.append)
      recorded ++= unrecorded
    }
}

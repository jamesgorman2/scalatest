package compatibility.recorders

import org.scalatest.Reporter
import org.scalatest.events.Event

class RecordingReporter extends Reporter {

  private val events = LineFile.namedBy("compatibility.events")

  def apply(event: Event): Unit = events.append(EventLine.of(event))
}

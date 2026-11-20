package compatibility.recorders

import sbt.testing.AnnotatedFingerprint
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Fingerprint
import sbt.testing.SubclassFingerprint

final class RecordingEventHandler(record: String => Unit) extends EventHandler {

  def handle(event: Event): Unit =
    record(
      List(
        "fullyQualifiedName=" + event.fullyQualifiedName(),
        "fingerprint=" + rendered(event.fingerprint()),
        "selector=" + event.selector(),
        "status=" + event.status(),
        "throwable=" + (if (event.throwable().isDefined()) EventLine.of(event.throwable().get()) else "none")
      ).mkString(", ")
    )

  private def rendered(fingerprint: Fingerprint): String =
    fingerprint match {
      case subclass: SubclassFingerprint =>
        "subclass(" + subclass.superclassName() + ", " + subclass.isModule() + ", " + subclass.requireNoArgConstructor() + ")"
      case annotated: AnnotatedFingerprint =>
        "annotated(" + annotated.annotationName() + ", " + annotated.isModule() + ")"
      case other => other.toString
    }
}

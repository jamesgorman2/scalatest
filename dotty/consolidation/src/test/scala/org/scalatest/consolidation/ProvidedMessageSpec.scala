package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.TopOfClass
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ProvidedMessageSpec extends AnyWordSpec with should.Matchers {

  private val location = Some(TopOfClass("samples.RowSuite"))

  private val throwable = Some(new DecodedThrowable("java.lang.RuntimeException", Some("boom"), None, false, Vector.empty))

  "A provided message" when {
    "read from an InfoProvided" should {
      "be an info message with its text, time, location, throwable and thread" in {
        ProvidedMessage.of(DecodedEvent.InfoProvided("hello", throwable, location, "main", 100)) shouldBe
          Some(ProvidedMessage(MessageKind.Info, "hello", Instant.ofEpochMilli(100), location, throwable, "main"))
      }
    }
    "read from a NoteProvided" should {
      "be a note message" in {
        ProvidedMessage.of(DecodedEvent.NoteProvided("hello", None, None, "main", 100)).map(_.kind) shouldBe Some(MessageKind.Note)
      }
    }
    "read from an AlertProvided" should {
      "be an alert message" in {
        ProvidedMessage.of(DecodedEvent.AlertProvided("hello", None, None, "main", 100)).map(_.kind) shouldBe Some(MessageKind.Alert)
      }
    }
    "read from a MarkupProvided" should {
      "be a markup message with its text and no throwable" in {
        ProvidedMessage.of(DecodedEvent.MarkupProvided("*bold*", location, "main", 100)) shouldBe
          Some(ProvidedMessage(MessageKind.Markup, "*bold*", Instant.ofEpochMilli(100), location, None, "main"))
      }
    }
    "read from an event that is not a message" should {
      "be absent" in {
        ProvidedMessage.of(DecodedEvent.TestStarting(location, "main", 100)) shouldBe None
      }
    }
  }
}

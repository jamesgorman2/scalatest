package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.Location

/** `word` is the kind as the outputs mark a message of it. */
private[consolidation] enum MessageKind(val word: String) {
  case Info extends MessageKind("info")
  case Note extends MessageKind("note")
  case Alert extends MessageKind("alert")
  case Markup extends MessageKind("markup")
}

/** An `info`, `note`, `alert` or `markup` message. */
private[consolidation] final case class ProvidedMessage(
  kind: MessageKind,
  text: String,
  at: Instant,
  location: Option[Location],
  throwable: Option[DecodedThrowable],
  threadName: String
)

private[consolidation] object ProvidedMessage {

  def of(event: DecodedEvent.Message): ProvidedMessage =
    event match {
      case info: DecodedEvent.InfoProvided => provided(MessageKind.Info, info.message, info.throwable, info)
      case note: DecodedEvent.NoteProvided => provided(MessageKind.Note, note.message, note.throwable, note)
      case alert: DecodedEvent.AlertProvided => provided(MessageKind.Alert, alert.message, alert.throwable, alert)
      case markup: DecodedEvent.MarkupProvided => provided(MessageKind.Markup, markup.text, None, markup)
    }

  private def provided(kind: MessageKind, text: String, throwable: Option[DecodedThrowable], event: DecodedEvent): ProvidedMessage =
    ProvidedMessage(kind, text, Instant.ofEpochMilli(event.timeStamp), event.location, throwable, event.threadName)
}

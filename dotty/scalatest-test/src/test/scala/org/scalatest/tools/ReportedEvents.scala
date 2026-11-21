package org.scalatest.tools

import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.NoteProvided
import org.scalatest.events.RecordableEvent
import org.scalatest.events.RunAborted
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestFailed
import org.scalatest.events.TestIgnored
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded

private[tools] object ReportedEvents {

  def of(events: Vector[Event]): Vector[String] = events.map(described)

  def described(event: Event): String =
    event match {
      case aborted: RunAborted => s"RunAborted: ${aborted.message}"
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case aborted: SuiteAborted => s"SuiteAborted ${aborted.suiteName}: ${aborted.message}"
      case opened: ScopeOpened => s"ScopeOpened ${opened.message}"
      case closed: ScopeClosed => s"ScopeClosed ${closed.message}"
      case starting: TestStarting => s"TestStarting ${starting.testName}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}${recordedIn(succeeded.recordedEvents)}"
      case failed: TestFailed => s"TestFailed ${failed.testName}: ${failed.message}${recordedIn(failed.recordedEvents)}"
      case ignored: TestIgnored => s"TestIgnored ${ignored.testName}"
      case info: InfoProvided => s"InfoProvided ${info.message}"
      case note: NoteProvided => s"NoteProvided ${note.message}"
      case alert: AlertProvided => s"AlertProvided ${alert.message}"
      case markup: MarkupProvided => s"MarkupProvided ${markup.text}"
      case other => other.getClass.getSimpleName
    }

  private def recordedIn(recorded: Seq[RecordableEvent]): String =
    if (recorded.isEmpty) "" else recorded.map(described).mkString(", recording ", ", ", "")
}

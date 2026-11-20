package compatibility.recorders

import org.scalatest.Reporter
import org.scalatest.events.*
import org.scalatest.exceptions.PropertyCheckFailedException
import org.scalatest.exceptions.StackDepth

import java.util.IdentityHashMap

class EventFieldsRecordingReporter extends Reporter {

  private val lines = LineFile.namedBy("compatibility.eventFields")

  def apply(event: Event): Unit = {
    lines.append("received " + EventFields.of(event, withTimeStamp = true))
    // Without its timestamp, which a test event log takes from when the test made the call that recorded it.
    recordedEvents(event).foreach(recorded => lines.append("recorded " + EventFields.of(recorded, withTimeStamp = false)))
  }

  private def recordedEvents(event: Event): Seq[Event] =
    event match {
      case e: TestSucceeded => e.recordedEvents
      case e: TestFailed => e.recordedEvents
      case e: TestPending => e.recordedEvents
      case e: TestCanceled => e.recordedEvents
      case _ => Nil
    }
}

private object EventFields {

  private val namesAndBookkeeping =
    Set("ordinal", "formatter", "rerunner", "payload", "recordedEvents", "suiteName", "suiteId", "suiteClassName", "testName", "testText", "nameInfo")

  def of(event: Event, withTimeStamp: Boolean): String = {
    val left = namesAndBookkeeping ++ leftByKind(event) ++ (if (withTimeStamp) Set.empty else Set("timeStamp"))
    val fields = event.productElementNames
      .zip(event.productIterator)
      .collect { case (name, value) if !left.contains(name) => name + "=" + rendered(value) }
    node(event) + " " + event.productPrefix + fields.mkString("(", ", ", ")")
  }

  private def leftByKind(event: Event): Set[String] =
    event match {
      case _: ScopeOpened | _: ScopeClosed | _: ScopePending => Set("message")
      case _: DiscoveryStarting => Set("configMap")
      case _ => Set.empty
    }

  private def node(event: Event): String =
    event match {
      case e: TestStarting => test(e.suiteId, e.testName)
      case e: TestSucceeded => test(e.suiteId, e.testName)
      case e: TestFailed => test(e.suiteId, e.testName)
      case e: TestIgnored => test(e.suiteId, e.testName)
      case e: TestPending => test(e.suiteId, e.testName)
      case e: TestCanceled => test(e.suiteId, e.testName)
      case e: SuiteStarting => suite(e.suiteId)
      case e: SuiteCompleted => suite(e.suiteId)
      case e: SuiteAborted => suite(e.suiteId)
      case e: ScopeOpened => named(Some(e.nameInfo))
      case e: ScopeClosed => named(Some(e.nameInfo))
      case e: ScopePending => named(Some(e.nameInfo))
      case e: InfoProvided => named(e.nameInfo)
      case e: AlertProvided => named(e.nameInfo)
      case e: NoteProvided => named(e.nameInfo)
      case e: MarkupProvided => named(e.nameInfo)
      case _ => "run"
    }

  private def named(nameInfo: Option[NameInfo]): String =
    nameInfo.fold("run") { info =>
      info.testName.fold(suite(info.suiteId))(test(info.suiteId, _))
    }

  private def suite(suiteId: String): String = "suite " + quoted(suiteId)

  private def test(suiteId: String, testName: String): String = "test " + quoted(suiteId) + " " + quoted(testName)

  private def rendered(value: Any): String =
    value match {
      case Some(inner) => "Some(" + rendered(inner) + ")"
      case None => "None"
      case throwable: Throwable => new Throwables().rendered(throwable)
      case text: String => quoted(text)
      case entries: scala.collection.Map[?, ?] =>
        entries.toList.map((key, entry) => String.valueOf(key) -> String.valueOf(entry)).sortBy(_._1)
          .map((key, entry) => quoted(key) + " -> " + quoted(entry))
          .mkString("{", ", ", "}")
      case items: Iterable[?] => items.map(rendered).mkString("[", ", ", "]")
      case other => String.valueOf(other)
    }

  private def quoted(text: String): String = NotationText.quoted(EventLine.withLineFeeds(text))

  private final class Throwables {

    private val ids = new IdentityHashMap[Throwable, Integer]

    def rendered(throwable: Throwable): String =
      Option(ids.get(throwable)).fold(renderedFirstTime(throwable))(id => "ref " + id)

    private def renderedFirstTime(throwable: Throwable): String = {
      ids.put(throwable, ids.size + 1)
      val stackDepth = throwable match {
        case failed: StackDepth =>
          " stackDepth(" + List(
            failed.failedCodeFileName.map(quoted),
            failed.failedCodeFilePathname.map(quoted),
            failed.failedCodeLineNumber.map(_.toString)
          ).map(_.getOrElse("?")).mkString(", ") + ")"
        case _ => ""
      }
      val propertyCheck = if (throwable.isInstanceOf[PropertyCheckFailedException]) " propertyCheck" else ""
      val frames = throwable.getStackTrace.toList.map { frame =>
        List(
          quoted(frame.getClassName),
          quoted(frame.getMethodName),
          Option(frame.getFileName).fold("?")(quoted),
          Some(frame.getLineNumber).filter(_ >= 0).fold("?")(_.toString),
          String.valueOf(frame.isNativeMethod),
          quoted(frame.toString)
        ).mkString("(", ", ", ")")
      }
      val cause = Option(throwable.getCause).fold("")(cause => " cause(" + rendered(cause) + ")")
      val suppressed = throwable.getSuppressed.toList.map(rendered)
      throwable.getClass.getName + " " + Option(throwable.getMessage).fold("null")(quoted) + stackDepth + propertyCheck +
        " frames" + frames.mkString("[", ", ", "]") + cause + " suppressed" + suppressed.mkString("[", ", ", "]")
    }
  }
}

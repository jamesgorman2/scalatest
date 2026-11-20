package compatibility.recorders

import org.scalatest.events.Event
import org.scalatest.events.NotationText
import org.scalatest.events.Ordinal
import org.scalatest.exceptions.StackDepth

object EventLine {

  private val fieldsNotCompared: Set[String] = Set("timeStamp", "duration", "threadName")

  def of(event: Event): String = rendered(event)

  /**
   * The text with each "\r\n" written as "\n". The fork separates the lines of the text it writes with the platform's
   * line separator, and 3.2.20 with "\n", so on Windows the same message differs only there.
   */
  def withLineFeeds(text: String): String = text.replace("\r\n", "\n")

  def of(throwable: Throwable): String = {
    val failedCode = throwable match {
      case stackDepth: StackDepth =>
        " at " + stackDepth.failedCodeFileName.getOrElse("?") + ":" + stackDepth.failedCodeLineNumber.fold("?")(_.toString)
      case _ => ""
    }
    val cause = Option(throwable.getCause).fold("")(cause => " caused by " + of(cause))
    throwable.getClass.getName + " " + Option(throwable.getMessage).fold("null")(quoted) + failedCode + cause
  }

  private def rendered(value: Any): String =
    value match {
      case event: Event => fields(event, fieldsNotCompared)
      case throwable: Throwable => of(throwable)
      case ordinal: Ordinal => ordinal.toList.mkString("Ordinal(", ", ", ")")
      case text: String => quoted(text)
      case items: Iterable[?] => items.map(rendered).mkString("[", ", ", "]")
      case product: Product => fields(product, Set.empty)
      case other => String.valueOf(other)
    }

  private def fields(product: Product, left: Set[String]): String =
    product.productElementNames
      .zip(product.productIterator)
      .collect { case (name, value) if !left.contains(name) => name + "=" + rendered(value) }
      .mkString(product.productPrefix + "(", ", ", ")")

  private def quoted(text: String): String = NotationText.quoted(withLineFeeds(text))
}

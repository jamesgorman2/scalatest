package compatibility.recorders

import org.scalatest.events.Event
import org.scalatest.events.Ordinal
import org.scalatest.exceptions.StackDepth

object EventLine {

  private val fieldsNotCompared: Set[String] = Set("timeStamp", "duration", "threadName")

  def of(event: Event): String = rendered(event)

  def ofThrowable(throwable: Throwable): String = {
    val failedCode = throwable match {
      case stackDepth: StackDepth =>
        " at " + stackDepth.failedCodeFileName.getOrElse("?") + ":" + stackDepth.failedCodeLineNumber.fold("?")(_.toString)
      case _ => ""
    }
    val cause = Option(throwable.getCause).fold("")(cause => " caused by " + ofThrowable(cause))
    throwable.getClass.getName + " " + Option(throwable.getMessage).fold("null")(quoted) + failedCode + cause
  }

  private def rendered(value: Any): String =
    value match {
      case event: Event => fields(event, fieldsNotCompared)
      case throwable: Throwable => ofThrowable(throwable)
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

  private def quoted(text: String): String =
    text.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' => f"\\u${char.toInt}%04x"
      case char => char.toString
    }.mkString("\"", "", "\"")
}

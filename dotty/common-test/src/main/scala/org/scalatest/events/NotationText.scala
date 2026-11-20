package org.scalatest.events

object NotationText {

  def quoted(text: String): String =
    text.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' || Character.isSurrogate(char) => f"\\u${char.toInt}%04x"
      case char => char.toString
    }.mkString("\"", "", "\"")
}

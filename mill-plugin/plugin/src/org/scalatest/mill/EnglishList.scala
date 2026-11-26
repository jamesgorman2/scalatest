package org.scalatest.mill

private[mill] object EnglishList {

  def of(items: Seq[String]): String =
    items match {
      case Seq() => ""
      case Seq(only) => only
      case _ => s"${items.init.mkString(", ")} and ${items.last}"
    }
}

package org.scalatest.mill

private[mill] object EnglishList {

  def of(items: Seq[String]): String = s"${items.init.mkString(", ")} and ${items.last}"
}

package org.scalatest.mill

final case class BuildComparison[A](withoutPlugin: A, withPlugin: A) {

  def describe(show: A => String): String =
    s"""Without the plugin:
       |${show(withoutPlugin)}
       |With the plugin:
       |${show(withPlugin)}""".stripMargin
}

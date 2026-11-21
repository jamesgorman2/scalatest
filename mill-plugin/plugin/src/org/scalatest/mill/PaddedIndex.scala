package org.scalatest.mill

private[mill] object PaddedIndex {

  def digits(count: Int): Int = count.toString.length

  def of(index: Int, count: Int): String = s"%0${digits(count)}d".format(index)
}

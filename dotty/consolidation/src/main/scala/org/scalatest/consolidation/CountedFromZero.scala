package org.scalatest.consolidation

private[consolidation] object CountedFromZero {

  def checked(count: Int, name: String): Int =
    if (count >= 0)
      count
    else
      throw new IllegalArgumentException(s"$name must not be negative, but was $count")
}

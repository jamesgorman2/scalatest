package org.scalatest.consolidation

private[consolidation] object CountedFromZero {

  def checked(value: Int, name: String): Int =
    if (value >= 0)
      value
    else
      throw new IllegalArgumentException(s"$name must not be negative, but was $value")
}

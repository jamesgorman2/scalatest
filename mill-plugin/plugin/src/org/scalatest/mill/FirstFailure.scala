package org.scalatest.mill

private[mill] object FirstFailure {

  def orValues[E, A](results: Seq[Either[E, A]]): Either[E, Seq[A]] = {
    val (failures, values) = results.partitionMap(identity)
    failures.headOption.toLeft(values)
  }
}

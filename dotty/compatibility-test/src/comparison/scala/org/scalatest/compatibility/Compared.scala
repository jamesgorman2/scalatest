package org.scalatest.compatibility

final case class Compared[A](upstream: A, fork: A) {
  def map[B](f: A => B): Compared[B] = Compared(f(upstream), f(fork))
}

object Compared {
  def firstDifference(lines: Compared[Seq[String]]): Option[String] =
    lines.upstream.map(Option(_)).zipAll(lines.fork.map(Option(_)), None, None).zipWithIndex.collectFirst {
      case ((upstreamLine, forkLine), index) if upstreamLine != forkLine =>
        s"line ${index + 1}: 3.2.20 wrote ${described(upstreamLine)}, the fork wrote ${described(forkLine)}"
    }

  private def described(line: Option[String]): String = line.fold("no line")(text => s"[$text]")
}

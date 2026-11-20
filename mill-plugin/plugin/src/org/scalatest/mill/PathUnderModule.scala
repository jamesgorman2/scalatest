package org.scalatest.mill

private[mill] opaque type PathUnderModule = Seq[String]

private[mill] object PathUnderModule {

  def apply(segments: String*): PathUnderModule = segments

  extension (path: PathUnderModule) {

    def segments: Seq[String] = path

    def resolve(segment: String): PathUnderModule = path :+ segment

    def render: String = path.mkString("/")
  }
}

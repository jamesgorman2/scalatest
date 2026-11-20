package org.scalatest.mill

/** A path under a module's folder, as its segments. With no segment, it is the module's folder. */
private[mill] opaque type PathUnderModule = Seq[String]

private[mill] object PathUnderModule {

  def apply(segments: String*): PathUnderModule = segments

  extension (path: PathUnderModule) {

    def segments: Seq[String] = path

    def resolve(segment: String): PathUnderModule = path :+ segment

    def render: String = path.mkString("/")
  }
}

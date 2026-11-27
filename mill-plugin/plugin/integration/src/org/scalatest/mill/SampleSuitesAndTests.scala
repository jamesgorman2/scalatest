package org.scalatest.mill

enum SampleSuite {
  case Alpha1
  case Alpha2
  case Beta1
}

enum SampleTest(val name: String, tag: Option[String]) {
  case Fast extends SampleTest("fast", Some("Fast"))
  case Slow extends SampleTest("slow", None)

  private def declaration: String = s"""test("$name"${tag.map(", " + _).getOrElse("")}) """

  def withEmptyBody: String = declaration + "{}"

  def withBody(body: String): String = declaration + s"{ $body }"

  def sendingWorkingDirectory: String = withBody("""info(System.getProperty("user.dir"))""")

  def creatingFileInWorkingDirectory(fileName: String): String =
    withBody(s"""java.nio.file.Files.write(java.nio.file.Paths.get("$fileName"), Array.emptyByteArray)""")

  def failing: String = withBody("""fail("fails as the scenario asks")""")
}

final case class SampleMarker(suite: SampleSuite, test: SampleTest) {
  def fileName: String = s"$suite.${test.name}"
}

object SampleMarker {
  val all: Seq[SampleMarker] = for {
    suite <- SampleSuite.values.toSeq
    test <- SampleTest.values.toSeq
  } yield SampleMarker(suite, test)
}

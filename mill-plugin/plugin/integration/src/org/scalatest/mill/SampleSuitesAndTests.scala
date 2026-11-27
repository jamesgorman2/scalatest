package org.scalatest.mill

enum SampleSuite {
  case Alpha1
  case Alpha2
  case Beta1
}

enum SampleTag {
  case Fast
}

final case class SampleTestSource(name: String, tag: Option[SampleTag]) {

  private def declaration: String = s"""test("$name"${tag.map(", " + _).getOrElse("")}) """

  def withEmptyBody: String = declaration + "{}"

  def withBody(body: String): String = declaration + s"{ $body }"

  def sendingWorkingDirectory: String = withBody(SampleTest.bodySendingWorkingDirectory)

  def creatingFileInWorkingDirectory(fileName: String): String =
    withBody(s"""java.nio.file.Files.write(java.nio.file.Paths.get("$fileName"), Array.emptyByteArray)""")

  def failing: String = withBody("""fail("fails as the scenario asks")""")

  // The several-modules fixture's suites tag none of their tests.
  def untagged: SampleTestSource = copy(tag = None)
}

enum SampleTest(val source: SampleTestSource) {
  case Fast extends SampleTest(SampleTestSource("fast", Some(SampleTag.Fast)))
  case Slow extends SampleTest(SampleTestSource("slow", None))

  def name: String = source.name
}

object SampleTest {

  val bodySendingWorkingDirectory: String = """info(System.getProperty("user.dir"))"""
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

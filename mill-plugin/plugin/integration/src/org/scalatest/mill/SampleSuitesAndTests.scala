package org.scalatest.mill

enum SampleSuite {
  case Alpha1
  case Alpha2
  case Beta1
}

enum SampleTag {
  case Fast
}

trait SampleTestSource {

  def name: String

  protected def tag: Option[SampleTag]

  private def declaration: String = s"""test("$name"${tag.map(", " + _).getOrElse("")}) """

  def withEmptyBody: String = declaration + "{}"

  def withBody(body: String): String = declaration + s"{ $body }"

  def sendingWorkingDirectory: String = withBody(SampleTest.bodySendingWorkingDirectory)

  def creatingFileInWorkingDirectory(fileName: String): String =
    withBody(s"""java.nio.file.Files.write(java.nio.file.Paths.get("$fileName"), Array.emptyByteArray)""")

  def failing: String = withBody("""fail("fails as the scenario asks")""")
}

enum SampleTest(val name: String, protected val tag: Option[SampleTag]) extends SampleTestSource {
  case Fast extends SampleTest("fast", Some(SampleTag.Fast))
  case Slow extends SampleTest("slow", None)

  // The several-modules fixture's suites tag none of their tests.
  def untagged: SampleTestSource = UntaggedSampleTest(name)
}

object SampleTest {

  val bodySendingWorkingDirectory: String = """info(System.getProperty("user.dir"))"""
}

private final case class UntaggedSampleTest(name: String) extends SampleTestSource {

  protected def tag: Option[SampleTag] = None
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

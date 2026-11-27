package org.scalatest.mill

enum SampleSuite {
  case Alpha1
  case Alpha2
  case Beta1
}

enum SampleTest(declaration: String) {
  case Fast extends SampleTest("""test("fast", Fast) """)
  case Slow extends SampleTest("""test("slow") """)

  def withEmptyBody: String = declaration + "{}"

  def withBody(body: String): String = declaration + s"{ $body }"

  def sendingWorkingDirectory: String = withBody("""info(System.getProperty("user.dir"))""")

  def creatingFileInWorkingDirectory(fileName: String): String =
    withBody(s"""java.nio.file.Files.write(java.nio.file.Paths.get("$fileName"), Array.emptyByteArray)""")

  def failing: String = withBody("""fail("fails as the scenario asks")""")
}

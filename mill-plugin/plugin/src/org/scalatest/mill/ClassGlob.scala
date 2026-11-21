package org.scalatest.mill

import java.util.regex.Pattern
import org.scalatest.events.TestClassName

private[mill] opaque type ClassGlob = String

private[mill] object ClassGlob {

  def apply(text: String): ClassGlob = text

  def keeping(classGlobs: Seq[ClassGlob]): TestClassName => Boolean =
    testClass => classGlobs.isEmpty || classGlobs.exists(_.matches(testClass))

  def noTestMatchedMessage(classGlobs: Seq[ClassGlob]): String =
    // Mill 1.3.1's `testOnly` fails with this message.
    s"Test selector does not match any test: ${classGlobs.map(_.text).mkString(" ")}\nRun discoveredTestClasses to see available tests"

  private def objectOrClassName(testClass: TestClassName): String =
    // As Mill 1.3.1's `testOnly` does, an object's class matches by the name of the object.
    testClass.value.stripSuffix("$")

  extension (glob: ClassGlob) {
    def text: String = glob

    def matches(testClass: TestClassName): Boolean = glob.regex.matcher(objectOrClassName(testClass)).matches()

    private def regex: Pattern = {
      val literalPieces = glob.split("\\*", -1).map(Pattern.quote)
      Pattern.compile(literalPieces.mkString(".*"))
    }
  }
}

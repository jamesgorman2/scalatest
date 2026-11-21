package org.scalatest.mill

import java.util.regex.Pattern
import org.scalatest.events.TestClassName

private[mill] opaque type ClassGlob = String

private[mill] object ClassGlob {

  def apply(text: String): ClassGlob = text

  def keeping(classGlobs: Seq[ClassGlob]): TestClassName => Boolean =
    testClass => classGlobs.isEmpty || classGlobs.exists(_.matches(testClass))

  extension (glob: ClassGlob) {
    def text: String = glob

    def matches(testClass: TestClassName): Boolean =
      // As Mill 1.3.1's `testOnly` does, an object's class matches by the name of the object.
      Pattern.compile(glob.split("\\*", -1).map(Pattern.quote).mkString(".*")).matcher(testClass.value.stripSuffix("$")).matches()
  }
}

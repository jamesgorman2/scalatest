package org.scalatest.compatibility

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ClassFileMethodsSpec extends AnyWordSpec with Matchers {

  private def methodsOfCompared: Seq[String] =
    ClassFileMethods.of(Files.readAllBytes(Paths.get(getClass.getResource("/org/scalatest/compatibility/Compared.class").toURI)))

  "The methods of a class file" should {
    "hold each method the class declares, as its name and erased descriptor" in {
      methodsOfCompared should contain("map(Lscala/Function1;)Lorg/scalatest/compatibility/Compared;")
    }
    "hold none of the class's fields" in {
      methodsOfCompared.filterNot(_.contains("(")) shouldBe Nil
    }
  }
}

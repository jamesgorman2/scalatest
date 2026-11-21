package org.scalatest.mill

import org.scalatest.events.TestClassName
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ClassGlobSpec extends AnyWordSpec with should.Matchers {

  private def matching(glob: String, classNames: String*): Seq[String] =
    classNames.filter(name => ClassGlob(glob).matches(TestClassName(name)))

  private val classNames: Seq[String] = Seq("samples.Alpha1", "samples.Alpha2", "samples.Beta1", "other.Alpha1")

  "A class glob" when {
    "it holds no *" should {
      "match only the name equal to it" in {
        matching("samples.Alpha1", (classNames :+ "samples.Alpha10")*) shouldBe Seq("samples.Alpha1")
      }

      "match a . only as a ." in {
        matching("samples.Alpha1", "samplesXAlpha1") shouldBe empty
      }

      "match an object's class by the name of the object" in {
        matching("samples.Fast", "samples.Fast$") shouldBe Seq("samples.Fast$")
      }
    }

    "it ends with *" should {
      "match every name that begins with the rest" in {
        matching("samples.Alpha*", classNames*) shouldBe Seq("samples.Alpha1", "samples.Alpha2")
      }
    }

    "it begins with *" should {
      "match every name that ends with the rest" in {
        matching("*1", classNames*) shouldBe Seq("samples.Alpha1", "samples.Beta1", "other.Alpha1")
      }
    }

    "it holds * between other characters" should {
      "match every name that begins with the part before it and ends with the part after it" in {
        matching("samples.*a2", classNames*) shouldBe Seq("samples.Alpha2")
      }
    }

    "it holds several *" should {
      "match any run of characters, including none, at each" in {
        matching("*.*Alpha*", (classNames :+ "Alpha")*) shouldBe Seq("samples.Alpha1", "samples.Alpha2", "other.Alpha1")
      }
    }

    "it is * alone" should {
      "match every name" in {
        matching("*", classNames*) shouldBe classNames
      }
    }
  }

  "Keeping test classes by class globs" when {
    "no class glob is given" should {
      "keep every class" in {
        classNames.filter(name => ClassGlob.keeping(Nil)(TestClassName(name))) shouldBe classNames
      }
    }

    "several class globs are given" should {
      "keep each class that at least one of them matches" in {
        val keeps = ClassGlob.keeping(Seq(ClassGlob("samples.Beta1"), ClassGlob("samples.Alpha2")))
        classNames.filter(name => keeps(TestClassName(name))) shouldBe Seq("samples.Alpha2", "samples.Beta1")
      }
    }
  }
}

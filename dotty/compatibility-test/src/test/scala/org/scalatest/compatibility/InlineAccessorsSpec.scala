package org.scalatest.compatibility

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class InlineAccessorsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers {

  private val classpaths = ComparisonClasspaths.load()

  Feature("Binary compatibility with ScalaTest 3.2.20") {
    Scenario("Code compiled against 3.2.20 for the JVM finds every inline accessor it calls") {
      Given("the inline accessors of the ScalaTest and Scalactic classes 3.2.20 publishes for the JVM")
      val upstream = InlineAccessors.onClasspath(classpaths.upstream)
      upstream.count(_.contains(".inline$applyImpl(")) shouldBe 34

      When("the inline accessors of this fork's classes are read")
      val fork = InlineAccessors.onClasspath(classpaths.fork)

      Then("each class of this fork declares every accessor 3.2.20's declares, under 3.2.20's name and erased signature")
      (upstream -- fork).toList.sorted shouldBe Nil
    }
  }
}

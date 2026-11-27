package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.Core
import org.scalatest.mill.tags.CoreTag

import java.lang.reflect.Modifier
import java.nio.file.Paths

@Core
final class EveryScenarioHasAnAreaTagSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers {

  private val areaTagNames: Set[String] = AreaTag.values.map(_.name).toSet

  private def scenariosWithoutAnAreaTag(suite: Suite): Seq[String] =
    suite.testNames.toSeq.filterNot(scenario => suite.tags.getOrElse(scenario, Set.empty).exists(areaTagNames.contains))

  private def isRunnableSuite(suiteClass: Class[?]): Boolean =
    classOf[Suite].isAssignableFrom(suiteClass) &&
      Modifier.isPublic(suiteClass.getModifiers) &&
      !Modifier.isAbstract(suiteClass.getModifiers) &&
      suiteClass.getConstructors.exists(_.getParameterCount == 0)

  private def integrationSuites: Seq[Suite] = {
    val classesFolder = os.Path(Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI))
    os.walk(classesFolder)
      .filter(file => file.ext == "class" && !file.last.contains('$'))
      .map(file => Class.forName(file.relativeTo(classesFolder).segments.mkString(".").stripSuffix(".class"), false, getClass.getClassLoader))
      .filter(isRunnableSuite)
      .filterNot(_ == getClass)
      .map(_.getDeclaredConstructor().newInstance().asInstanceOf[Suite])
  }

  Feature("Every integration scenario carries an area tag") {

    Scenario("Every scenario of every integration suite carries at least one area tag, from its suite or its own") {
      Given("every suite this test module runs, apart from this one")
      val suites = integrationSuites
      suites.map(_.suiteName) should contain allOf ("EveryWorkerSpec", "HeaderPluginSpec")

      When("the tags of each suite's scenarios are read")
      val scenarioLabelsWithoutAnAreaTag = suites.flatMap(suite => scenariosWithoutAnAreaTag(suite).map(scenario => s"${suite.suiteName}: $scenario"))

      Then("no scenario lacks an area tag")
      scenarioLabelsWithoutAnAreaTag shouldBe empty
    }

    Scenario("A scenario that carries only the Core tag, or no tag, lacks an area tag") {
      Given("a suite whose scenarios carry the Workers tag, the Core tag, and no tag")
      val suite = new AnyFeatureSpec {
        Feature("F") {
          Scenario("Workers", AreaTag.Workers) {}
          Scenario("Core", CoreTag) {}
          Scenario("None") {}
        }
      }

      When("the tags of its scenarios are read")
      val scenarios = scenariosWithoutAnAreaTag(suite)

      Then("the scenarios carrying the Core tag and no tag lack an area tag")
      scenarios shouldBe Seq("Feature: F Scenario: Core", "Feature: F Scenario: None")
    }
  }
}

package org.scalatest

/** A scope or test of a suite's registration tree, named and labelled as a test event log's structure gives it. */
private[scalatest] sealed abstract class RegisteredNode

/** A scope, holding its scopes and tests in the order registered. */
private[scalatest] final case class RegisteredScope(name: String, label: String, nodes: List[RegisteredNode]) extends RegisteredNode

/**
 * A test. Its `label` is the text ScalaTest's formatter gives its closing event, and `labelWhenIgnored` the text it
 * gives its `TestIgnored`, each without leading indentation.
 */
private[scalatest] final case class RegisteredTest(name: String, testName: String, label: String, labelWhenIgnored: String) extends RegisteredNode

private[scalatest] object RegisteredNode {

  // Level 0 makes each formatter add no indentation, so none has to be told from spaces the text itself starts with.
  private val unindented = 0

  def scopeLabel(textWithChildPrefix: String): String =
    Suite.getIndentedTextForInfo(textWithChildPrefix, unindented, false, false).formattedText

  def test(name: String, testName: String, textWithChildPrefix: String, includeIcon: Boolean): RegisteredTest =
    RegisteredTest(
      name,
      testName,
      Suite.getIndentedTextForTest(textWithChildPrefix, unindented, includeIcon).formattedText,
      Suite.getIndentedTextForTest(textWithChildPrefix, unindented, true).formattedText
    )

  private val flatSpecVerbs = List("should ", "must ", "can ")

  /** The name of a FlatSpec test registered through a verb: its text without the verb that registration puts before it. */
  def flatSpecTestName(testText: String): String =
    flatSpecVerbs.find(verb => testText.startsWith(verb)).fold(testText)(verb => testText.substring(verb.length))

  /** The name of a FeatureSpec scope: its text without what `Feature` puts before it. */
  def featureName(descriptionText: String): String = descriptionText.stripPrefix(Resources.feature(""))

  /** The name of a FeatureSpec test: its text without what `Scenario` puts before it. */
  def scenarioName(testText: String): String = testText.stripPrefix(Resources.scenario(""))

  /** The test of a suite with no registration tree, labelled as `Suite.runTests` labels a test it ignores. */
  def unregisteredTest(testName: String): RegisteredTest = {
    val label = Suite.getEscapedIndentedTextForTest(testName, 1, true).formattedText
    RegisteredTest(testName, testName, label, label)
  }
}

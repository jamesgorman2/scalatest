package org.scalatest

/** A scope or test of a suite's registration tree, named and labelled as a test event log's structure gives it. */
private[scalatest] sealed abstract class RegisteredNode

/** `nodes` are in the order registered. `text` is the text the scope is registered with. */
private[scalatest] final case class RegisteredScope(name: String, text: String, label: String, nodes: List[RegisteredNode]) extends RegisteredNode

/**
 * A test. Its `label` is the text ScalaTest's formatter gives its closing event, and `labelWhenIgnored` the text it
 * gives its `TestIgnored`, each without leading indentation.
 */
private[scalatest] final case class RegisteredTest(name: String, testName: String, label: String, labelWhenIgnored: String) extends RegisteredNode

private[scalatest] object RegisteredNode {

  // Level 0 makes each formatter add no indentation, so none has to be told apart from spaces the text itself starts with.
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

  def featureName(descriptionText: String): String = descriptionText.stripPrefix(Resources.feature(""))

  def scenarioName(testText: String): String = testText.stripPrefix(Resources.scenario(""))

  // Labelled as `Suite.runTests` labels a test it ignores.
  def unregisteredTest(testName: String): RegisteredTest = {
    val label = Suite.getEscapedIndentedTextForTest(testName, 1, true).formattedText
    RegisteredTest(testName, testName, label, label)
  }
}

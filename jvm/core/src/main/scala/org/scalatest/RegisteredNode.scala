package org.scalatest

private[scalatest] sealed abstract class RegisteredNode

private[scalatest] final case class RegisteredScope(name: String, text: String, label: String, nodes: List[RegisteredNode]) extends RegisteredNode

private[scalatest] final case class RegisteredTest(name: String, scalaTestName: String, label: String, labelWhenIgnored: String) extends RegisteredNode

private[scalatest] object RegisteredNode {

  // Labels carry no indentation, which could not be told apart from spaces the text itself starts with.
  private val unindented = 0

  def scopeLabel(textWithChildPrefix: String): String =
    Suite.getIndentedTextForInfo(textWithChildPrefix, unindented, false, false).formattedText

  def test(name: String, scalaTestName: String, textWithChildPrefix: String, includeIcon: Boolean): RegisteredTest =
    RegisteredTest(
      name,
      scalaTestName,
      Suite.getIndentedTextForTest(textWithChildPrefix, unindented, includeIcon).formattedText,
      Suite.getIndentedTextForTest(textWithChildPrefix, unindented, true).formattedText
    )

  private val flatSpecVerbs = List("should ", "must ", "can ")

  def flatSpecTestName(testText: String): String =
    flatSpecVerbs.find(verb => testText.startsWith(verb)).fold(testText)(verb => testText.substring(verb.length))

  def featureName(descriptionText: String): String = descriptionText.stripPrefix(Resources.feature(""))

  def scenarioName(testText: String): String = testText.stripPrefix(Resources.scenario(""))

  def unregisteredTest(testName: String): RegisteredTest = {
    // As `Suite.runTests` labels a test it ignores.
    val label = Suite.getEscapedIndentedTextForTest(testName, 1, true).formattedText
    RegisteredTest(testName, testName, label, label)
  }
}

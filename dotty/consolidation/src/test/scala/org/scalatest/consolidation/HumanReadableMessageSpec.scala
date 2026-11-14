package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableMessageSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  /** An event log of a test that succeeds after sending an info, a note, an alert and a markup message. */
  private def testWithMessages(info: String): String =
    s"""
    run started
    structure
      suite "StackFunSuite", class "samples.StackFunSuite"
        test "is empty", label "- is empty"
    suite "StackFunSuite": SuiteStarting
    test "is empty": TestStarting
    $info
    test "is empty": NoteProvided(message: "a note")
    test "is empty": AlertProvided(message: "an alert")
    test "is empty": MarkupProvided(text: "*markup*")
    test "is empty": TestSucceeded
    suite "StackFunSuite": SuiteCompleted
    run ended completed
    """

  Feature("Messages sit under the node they attach to, each marked with its kind") {

    Scenario("A test's messages sit under it in the order they arrived, info, note and markup in green and alerts in yellow") {
      Given("an event log of a test that sends an info, a note, an alert and a markup message, then succeeds")
      val eventLog = testWithMessages("""test "is empty": InfoProvided(message: "checked size")""")

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each message sits under the test's line, marked with its kind")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFunSuite:{reset}
          |    {green}- is empty{reset}
          |      {green}[info] checked size{reset}
          |      {green}[note] a note{reset}
          |      {yellow}[alert] an alert{reset}
          |      {green}[markup] *markup*{reset}""".stripExpectedMargin
    }

    Scenario("A message's throwable sits under the message, with its cause") {
      Given("an event log of a test whose info carries a throwable with a cause")
      val eventLog = testWithMessages(
        """test "is empty": InfoProvided(message: "checked size", throwable: java.lang.IllegalStateException "size unknown" caused by java.io.IOException "closed")"""
      )

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the throwable's first line and its `Cause:` line sit under the info, each with its frame lines under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFunSuite:{reset}
          |    {green}- is empty{reset}
          |      {green}[info] checked size{reset}
          |        {green}java.lang.IllegalStateException: size unknown{reset}
          |          {green}...{reset}
          |        {green}Cause: java.io.IOException: closed{reset}
          |          {green}...{reset}
          |      {green}[note] a note{reset}
          |      {yellow}[alert] an alert{reset}
          |      {green}[markup] *markup*{reset}""".stripExpectedMargin
    }

    Scenario("A failed test's messages follow its failure, though they arrived before it") {
      Given("an event log of a test that sends an info, a note and an alert, then fails")
      val eventLog =
        """
        run started
        structure
          suite "StackFlatSpec", class "samples.StackFlatSpec"
            scope "A Stack", label "A Stack"
              test "have size 0", label "- must have size 0"
        suite "StackFlatSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": InfoProvided(message: "checked")
        test "have size 0": NoteProvided(message: "a note")
        test "have size 0": AlertProvided(message: "an alert")
        test "have size 0": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the failure's lines sit under the test's line, then its messages")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}...{reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }
  }

  Feature("A suite's or scope's message sits among the node's children") {

    Scenario("A scope's message sits after the children that had started when it arrived, and before the next") {
      Given("an event log of a scope whose info arrives after its first test and before its nested scope opens")
      val eventLog =
        """
        run started
        structure
          suite "ScopeInfoFunSpec", class "samples.ScopeInfoFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              scope "when full", label "when full"
                test "pops", label "- pops"
        suite "ScopeInfoFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "when full": ScopeClosed
        scope "A Stack": ScopeClosed
        suite "ScopeInfoFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the info sits under the scope's line, between its test and its nested scope")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ScopeInfoFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- is empty{reset}
          |      {green}[info] an info in a scope{reset}
          |      {green}when full{reset}
          |        {green}- pops{reset}""".stripExpectedMargin
    }

    Scenario("A suite's messages sit under its name line, each after the children that had started when it arrived") {
      Given("an event log of a suite that sends a message before its first test, two after it, and one after its nested suite")
      val eventLog =
        """
        run started
        structure
          suite "OuterSpec", class "samples.OuterSpec"
            test "first", label "- first"
            test "second", label "- second"
            suite "InnerSpec", class "samples.InnerSpec"
              test "inner", label "- inner"
        suite "OuterSpec": SuiteStarting
        suite "OuterSpec": InfoProvided(message: "before any test")
        test "first": TestStarting
        test "first": TestSucceeded
        suite "OuterSpec": NoteProvided(message: "after the first test")
        suite "OuterSpec": AlertProvided(message: "still after the first test")
        test "second": TestStarting
        test "second": TestSucceeded
        suite "InnerSpec": SuiteStarting
        test "inner": TestStarting
        test "inner": TestSucceeded
        suite "InnerSpec": SuiteCompleted
        suite "OuterSpec": MarkupProvided(text: "after the nested suite")
        suite "OuterSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each message sits under the suite's name line, placed by the children that had started, in the order they arrived")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSpec:{reset}
          |    {green}[info] before any test{reset}
          |    {green}- first{reset}
          |    {green}[note] after the first test{reset}
          |    {yellow}[alert] still after the first test{reset}
          |    {green}- second{reset}
          |    {green}InnerSpec:{reset}
          |      {green}- inner{reset}
          |    {green}[markup] after the nested suite{reset}""".stripExpectedMargin
    }

    Scenario("A message sits after the last started child in declared order, though the children started out of that order") {
      Given("an event log of a suite whose second test runs before its first, then sends an info")
      val eventLog =
        """
        run started
        structure
          suite "RandomOrderSpec", class "samples.RandomOrderSpec"
            test "first", label "- first"
            test "second", label "- second"
            test "third", label "- third"
        suite "RandomOrderSpec": SuiteStarting
        test "second": TestStarting
        test "second": TestSucceeded
        suite "RandomOrderSpec": InfoProvided(message: "after the second test")
        test "first": TestStarting
        test "first": TestSucceeded
        test "third": TestStarting
        test "third": TestSucceeded
        suite "RandomOrderSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the info sits after the second test and before the third")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}RandomOrderSpec:{reset}
          |    {green}- first{reset}
          |    {green}- second{reset}
          |    {green}[info] after the second test{reset}
          |    {green}- third{reset}""".stripExpectedMargin
    }
  }
}

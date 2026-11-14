package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableOutcomeSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  /** An event log of one suite holding the node `nodeLine`, whose events are `nodeEvents`. */
  private def eventLogOf(nodeLine: String, nodeEvents: String): String =
    s"""
    run started
    structure
      suite "OutcomeSpec", class "samples.OutcomeSpec"
        $nodeLine
    suite "OutcomeSpec": SuiteStarting
    $nodeEvents
    suite "OutcomeSpec": SuiteCompleted
    run ended completed
    """

  private def outputWithNodeLine(nodeLine: String): String =
    s"""{cyan}[core.test]{reset}
       |  {green}OutcomeSpec:{reset}
       |    $nodeLine""".stripExpectedMargin

  Feature("A scope's or test's line: its label, then its outcome's suffix") {

    Scenario("A test that succeeded shows its label alone, in green") {
      Given("an event log of a test that succeeded")
      val eventLog = eventLogOf(
        """test "adds", label "- adds"""",
        """test "adds": TestStarting
          |    test "adds": TestSucceeded""".stripMargin
      )

      When("the library consolidates it")
      Then("the test's line is its label, in green")
      consolidated(eventLog) shouldBe outputWithNodeLine("{green}- adds{reset}")
    }

    Scenario("A test that failed shows `*** FAILED ***`, in red") {
      Given("an event log of a test that failed")
      val eventLog = eventLogOf(
        """test "adds", label "- adds"""",
        """test "adds": TestStarting
          |    test "adds": TestFailed""".stripMargin
      )

      When("the library consolidates it")
      Then("the test's line is its label and `*** FAILED ***`, in red")
      consolidated(eventLog) shouldBe outputWithNodeLine("{red}- adds *** FAILED ***{reset}")
    }

    Scenario("A test that was ignored shows `!!! IGNORED !!!`, in yellow") {
      Given("an event log of an ignored test whose label has no leading `- `")
      val eventLog = eventLogOf(
        """test "adds", label "Scenario: adds"""",
        """test "adds": TestIgnored"""
      )

      When("the library consolidates it")
      Then("the test's line is its label and `!!! IGNORED !!!`, in yellow")
      consolidated(eventLog) shouldBe outputWithNodeLine("{yellow}Scenario: adds !!! IGNORED !!!{reset}")
    }

    Scenario("A test that is pending shows `(pending)`, in yellow") {
      Given("an event log of a pending test")
      val eventLog = eventLogOf(
        """test "adds", label "- adds"""",
        """test "adds": TestStarting
          |    test "adds": TestPending""".stripMargin
      )

      When("the library consolidates it")
      Then("the test's line is its label and `(pending)`, in yellow")
      consolidated(eventLog) shouldBe outputWithNodeLine("{yellow}- adds (pending){reset}")
    }

    Scenario("A test that was cancelled shows `!!! CANCELED !!!`, in yellow") {
      Given("an event log of a cancelled test")
      val eventLog = eventLogOf(
        """test "adds", label "- adds"""",
        """test "adds": TestStarting
          |    test "adds": TestCanceled""".stripMargin
      )

      When("the library consolidates it")
      Then("the test's line is its label and `!!! CANCELED !!!`, in yellow")
      consolidated(eventLog) shouldBe outputWithNodeLine("{yellow}- adds !!! CANCELED !!!{reset}")
    }

    Scenario("A scope with no outcome shows its label alone, in green") {
      Given("an event log of a scope that opened and closed around a test")
      val eventLog = eventLogOf(
        """scope "A calculator", label "A calculator"
          |          test "adds", label "- adds"""".stripMargin,
        """scope "A calculator": ScopeOpened
          |    test "adds": TestStarting
          |    test "adds": TestSucceeded
          |    scope "A calculator": ScopeClosed""".stripMargin
      )

      When("the library consolidates it")
      Then("the scope's line is its label, in green")
      consolidated(eventLog) shouldBe outputWithNodeLine(
        """{green}A calculator{reset}
          |      {green}- adds{reset}""".stripExpectedMargin
      )
    }

    Scenario("A scope that is pending shows `(pending)`, in yellow") {
      Given("an event log of a pending scope whose label begins `- `")
      val eventLog = eventLogOf(
        """scope "A calculator", label "- A calculator"""",
        """scope "A calculator": ScopePending"""
      )

      When("the library consolidates it")
      Then("the scope's line is its label and `(pending)`, in yellow")
      consolidated(eventLog) shouldBe outputWithNodeLine("{yellow}- A calculator (pending){reset}")
    }
  }

  Feature("Nodes without an outcome of their own when their suite aborts") {

    val abortedPartway =
      """
      run started
      structure
        suite "PartlyRunSpec", class "samples.PartlyRunSpec"
          test "ran", label "- ran"
          scope "Open", label "Open"
            test "in progress", label "- in progress"
          scope "Never opened", label "Never opened"
            test "never started", label "- never started"
          test "never reached", label "never reached"
      suite "PartlyRunSpec": SuiteStarting
      test "ran": TestStarting
      test "ran": TestSucceeded
      scope "Open": ScopeOpened
      test "in progress": TestStarting
      suite "PartlyRunSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
      run ended completed
      """

    val abortedPartwayOutput =
      """{cyan}[core.test]{reset}
        |  {green}PartlyRunSpec:{reset}
        |    {green}- ran{reset}
        |    {green}Open{reset}{red} *** ABORTED ***{reset}
        |      {red}- in progress *** ABORTED ***{reset}
        |    {green}Never opened{reset}{red} *** NOT RUN ***{reset}
        |      {red}(1 test not run){reset}
        |    {red}never reached *** NOT RUN ***{reset}
        |    {red}samples.PartlyRunSpec *** ABORTED ***{reset}
        |      {red}java.lang.RuntimeException: database unreachable{reset}
        |        {red}...{reset}""".stripExpectedMargin

    Scenario("A test that had not started when its suite aborted shows `*** NOT RUN ***`, in red") {
      Given("an event log of a suite that aborted before a test whose label has no leading `- ` started")
      When("the library consolidates it")
      val output = consolidated(abortedPartway)

      Then("the test's line is its label and `*** NOT RUN ***`, in red")
      output shouldBe abortedPartwayOutput
    }

    Scenario("A test open when its suite aborted shows `*** ABORTED ***`, in red") {
      Given("an event log of a suite that aborted while a test was running")
      When("the library consolidates it")
      val output = consolidated(abortedPartway)

      Then("the test's line is its label and `*** ABORTED ***`, in red")
      output shouldBe abortedPartwayOutput
    }

    Scenario("A scope that had not opened when its suite aborted shows its label in green and `*** NOT RUN ***` in red") {
      Given("an event log of a suite that aborted before a scope opened")
      When("the library consolidates it")
      val output = consolidated(abortedPartway)

      Then("the scope's line is its label in green, then `*** NOT RUN ***` in red")
      output shouldBe abortedPartwayOutput
    }

    Scenario("A scope open when its suite aborted shows its label in green and `*** ABORTED ***` in red") {
      Given("an event log of a suite that aborted while a scope was open")
      When("the library consolidates it")
      val output = consolidated(abortedPartway)

      Then("the scope's line is its label in green, then `*** ABORTED ***` in red")
      output shouldBe abortedPartwayOutput
    }

    Scenario("A test that completed before its suite aborted keeps its own outcome") {
      Given("an event log of a suite that aborted after one test succeeded")
      When("the library consolidates it")
      val output = consolidated(abortedPartway)

      Then("the test's line is its label, in green")
      output shouldBe abortedPartwayOutput
    }
  }

  Feature("No line shows an outcome rolled up from the nodes below it") {

    Scenario("A failed test leaves its scope, suite and label without a suffix") {
      Given("an event log of a scope holding a test that failed and one that succeeded")
      val eventLog =
        """
        run started
        structure
          suite "FailingFunSpec", class "samples.FailingFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "pops", label "- pops"
        suite "FailingFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "FailingFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the label's, the suite's and the scope's lines carry no suffix, and only the test's line shows `*** FAILED ***`")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}FailingFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- is empty *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}...{reset}
          |      {green}- pops{reset}""".stripExpectedMargin
    }
  }

  Feature("Each line of a multi-line label sits where its first line does") {

    Scenario("A test that succeeded shows each line of its label at its depth, each in green") {
      Given("an event log of a test whose label holds two lines, which succeeded")
      val eventLog = eventLogOf(
        """test "adds", label "- adds\nand carries"""",
        """test "adds": TestStarting
          |    test "adds": TestSucceeded""".stripMargin
      )

      When("the library consolidates it")
      Then("each line of the test's label sits at the test's depth, coloured on its own")
      consolidated(eventLog) shouldBe outputWithNodeLine(
        """{green}- adds{reset}
          |    {green}and carries{reset}""".stripExpectedMargin
      )
    }

    Scenario("A scope open when its suite aborted shows each line of its label in green, and `*** ABORTED ***` on the last") {
      Given("an event log of a suite that aborted while a scope whose label holds two lines was open")
      val eventLog =
        """
        run started
        structure
          suite "AbortedSpec", class "samples.AbortedSpec"
            scope "A calculator", label "A calculator\nin base 2"
              test "adds", label "- adds"
        suite "AbortedSpec": SuiteStarting
        scope "A calculator": ScopeOpened
        test "adds": TestStarting
        test "adds": TestSucceeded
        suite "AbortedSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each line of the scope's label sits at the scope's depth in green, and the last carries `*** ABORTED ***` in red")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}AbortedSpec:{reset}
          |    {green}A calculator{reset}
          |    {green}in base 2{reset}{red} *** ABORTED ***{reset}
          |      {green}- adds{reset}
          |    {red}samples.AbortedSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }
  }
}

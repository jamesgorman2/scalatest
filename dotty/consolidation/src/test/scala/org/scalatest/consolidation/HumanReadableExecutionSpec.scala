package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableExecutionSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  Feature("Nodes that ran more than once") {

    Scenario("A test shows a line for each execution, each with that execution's outcome, failure and messages") {
      Given("an event log of a test that failed, then succeeded when run again, beside a test that ran once")
      val eventLog =
        """
        run started
        structure
          suite "RetrySuite", class "samples.RetrySuite"
            test "flaky", label "- flaky"
            test "steady", label "- steady"
        suite "RetrySuite": SuiteStarting
        test "flaky": TestStarting
        test "flaky": InfoProvided(message: "first try")
        test "flaky": TestFailed(throwable: java.lang.IllegalStateException "not yet")
        test "steady": TestStarting
        test "steady": TestSucceeded
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: InfoProvided(message: "second try")
        test "flaky" execution 2: TestSucceeded
        suite "RetrySuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("both executions of the test sit together at its declared position, in the order they started")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}RetrySuite:{reset}
          |    {red}- flaky *** FAILED ***{reset}
          |      {red}java.lang.IllegalStateException: not yet{reset}
          |        {red}...{reset}
          |      {green}[info] first try{reset}
          |    {green}- flaky{reset}
          |      {green}[info] second try{reset}
          |    {green}- steady{reset}""".stripExpectedMargin
    }

    Scenario("A suite's message sits between the executions of a test that ran before and after it") {
      Given("an event log of a suite that sends an info between a test's two executions")
      val eventLog =
        """
        run started
        structure
          suite "RetrySuite", class "samples.RetrySuite"
            test "flaky", label "- flaky"
        suite "RetrySuite": SuiteStarting
        test "flaky": TestStarting
        test "flaky": TestFailed(throwable: java.lang.IllegalStateException "not yet")
        suite "RetrySuite": InfoProvided(message: "retrying")
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: TestSucceeded
        suite "RetrySuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the info sits after the test's first execution and before its second")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}RetrySuite:{reset}
          |    {red}- flaky *** FAILED ***{reset}
          |      {red}java.lang.IllegalStateException: not yet{reset}
          |        {red}...{reset}
          |    {green}[info] retrying{reset}
          |    {green}- flaky{reset}""".stripExpectedMargin
    }

    Scenario("Each execution of a scope sits at the scope's declared position, holding the executions of its tests that ran in it") {
      Given("an event log of a scope and its test that ran twice, either side of a sibling scope")
      val eventLog =
        """
        run started
        structure
          suite "TwiceFunSpec", class "samples.TwiceFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        suite "TwiceFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
        scope "A Queue": ScopeOpened
        test "polls": TestStarting
        test "polls": TestSucceeded
        scope "A Queue": ScopeClosed
        scope "A Stack" execution 2: ScopeOpened
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "TwiceFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("both executions of the scope come before the sibling scope, each holding the test's execution that ran in it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}TwiceFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- pops *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}...{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}
          |    {green}A Queue{reset}
          |      {green}- polls{reset}""".stripExpectedMargin
    }

    Scenario("A test's execution sits under the scope execution its events name, though the scope's executions overlap") {
      Given("an event log of a scope opened twice before either execution of its test starts")
      val eventLog =
        """
        run started
        structure
          suite "OverlapFunSpec", class "samples.OverlapFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "OverlapFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        scope "A Stack" execution 2: ScopeOpened
        test "pops", parent execution 1: TestStarting
        test "pops" execution 2, parent execution 2: TestStarting
        test "pops" execution 2, parent execution 2: TestSucceeded
        test "pops", parent execution 1: TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack" execution 2: ScopeClosed
        scope "A Stack": ScopeClosed
        suite "OverlapFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each execution of the test sits under the scope execution it ran in")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OverlapFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- pops *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}...{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}""".stripExpectedMargin
    }

    Scenario("A scope's execution shows no line for a test that ran only in another, and a test that never started shows once, under the latest") {
      Given("an event log of a scope that ran twice, one test running in both, one in the second only and one never")
      val eventLog =
        """
        run started
        structure
          suite "PartFunSpec", class "samples.PartFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "peeks", label "- peeks"
              test "grows", label "- grows"
        suite "PartFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        scope "A Stack" execution 2: ScopeOpened
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        test "peeks": TestStarting
        test "peeks": TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "PartFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the first execution of the scope holds only the test that ran in it, and the second the rest")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}PartFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}
          |      {green}- peeks{reset}
          |      {red}- grows *** NOT RUN ***{reset}""".stripExpectedMargin
    }

    Scenario("A suite run from two tests shows its name line once, and no abort line, though no event is assigned to it") {
      Given("an event log of a nested suite whose scope two tests of the suite nesting it each ran")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "first", label "- first"
            test "second", label "- second"
        suite "OuterSuite": SuiteStarting
        test "first": TestStarting
        structure under suite "OuterSuite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "first": TestSucceeded
        test "second": TestStarting
        scope "A helper" execution 2: ScopeOpened
        test "helps" execution 2: TestStarting
        test "helps" execution 2: TestSucceeded
        scope "A helper" execution 2: ScopeClosed
        test "second": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the nested suite follows the tests, its name line once, with both executions of its scope under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuite:{reset}
          |    {green}- first{reset}
          |    {green}- second{reset}
          |    {green}HelperSuite:{reset}
          |      {green}A helper{reset}
          |        {green}- helps{reset}
          |      {green}A helper{reset}
          |        {green}- helps{reset}""".stripExpectedMargin
    }

    Scenario("A suite that ran twice shows its name line once, then what each execution produced, in the order they started") {
      Given("an event log of a suite that aborted after its test ran, then ran again and completed")
      val eventLog =
        """
        run started
        structure
          suite "TwiceSuite", class "samples.TwiceSuite"
            test "connects", label "- connects"
        suite "TwiceSuite": SuiteStarting
        test "connects": TestStarting
        test "connects": TestSucceeded
        suite "TwiceSuite": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        suite "TwiceSuite" execution 2: SuiteStarting
        test "connects" execution 2: TestStarting
        test "connects" execution 2: TestSucceeded
        suite "TwiceSuite" execution 2: SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the first execution's test and abort lines come before the second execution's test")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}TwiceSuite:{reset}
          |    {green}- connects{reset}
          |    {red}samples.TwiceSuite *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}
          |    {green}- connects{reset}""".stripExpectedMargin
    }
  }

  Feature("A suite whose structure the event log holds more than once") {

    Scenario("Every execution of a node is shown at the parent, position and label the suite's last structure gives it") {
      Given("an event log whose further structure reorders a scope's tests, relabels one and moves one under the scope")
      val eventLog =
        """
        run started
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "peeks", label "- peeks"
            test "A Stack grows", label "- A Stack grows"
        suite "StackSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "peeks": TestStarting
        test "peeks": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "grows", label "- grows"
              test "peeks", label "- peeks"
              test "pops", label "- must pop"
        scope "A Stack" execution 2: ScopeOpened
        test "grows": TestStarting
        test "grows": TestSucceeded
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "StackSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each execution's tests appear in the last structure's order and with its labels, the moved test under the scope")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- peeks{reset}
          |      {red}- must pop *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}...{reset}
          |    {green}A Stack{reset}
          |      {green}- grows{reset}
          |      {green}- must pop{reset}""".stripExpectedMargin
    }

    Scenario("A test that ran but the last structure lacks keeps its place, after the test the last structure holds there") {
      Given("an event log whose further structure drops a test that ran, and adds one that never starts")
      val eventLog =
        """
        run started
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "is empty", label "- is empty"
        suite "ShrinkFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "peeks", label "- peeks"
        suite "ShrinkFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the dropped test follows the test now at its position, and the added test is not run")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ShrinkFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- is empty{reset}
          |      {green}- pops{reset}
          |      {red}- peeks *** NOT RUN ***{reset}""".stripExpectedMargin
    }

    Scenario("A scope whose test ran but the last structure lacks keeps its place, after the scope the last structure holds there") {
      Given("an event log whose further structure drops a scope whose test ran")
      val eventLog =
        """
        run started
        structure
          suite "DropFunSpec", class "samples.DropFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        suite "DropFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "DropFunSpec", class "samples.DropFunSpec"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        scope "A Queue": ScopeOpened
        test "polls": TestStarting
        test "polls": TestSucceeded
        scope "A Queue": ScopeClosed
        suite "DropFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the dropped scope and its test follow the scope now at its position")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}DropFunSpec:{reset}
          |    {green}A Queue{reset}
          |      {green}- polls{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}""".stripExpectedMargin
    }

    /** An event log whose further structure replaces a test that never started, followed by `events`, indented as these lines are. */
    def replacedTestThen(events: String): String =
      s"""
        run started
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "local", label "- local"
        suite "ConfigSpec": SuiteStarting
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "remote", label "- remote"
        $events
        """

    Scenario("A test that never started and the last structure lacks is not shown") {
      Given("an event log whose further structure replaces a test that never started with one that runs")
      val eventLog = replacedTestThen(
        """
        scope "A config": ScopeOpened
        test "remote": TestStarting
        test "remote": TestSucceeded
        scope "A config": ScopeClosed
        suite "ConfigSpec": SuiteCompleted
        run ended completed
        """
      )

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("only the test the last structure holds is shown")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ConfigSpec:{reset}
          |    {green}A config{reset}
          |      {green}- remote{reset}""".stripExpectedMargin
    }

    Scenario("A test that never started and the last structure lacks is not counted in a collapsed subtree") {
      Given("an event log whose further structure replaces a test, then whose suite aborts before any test starts")
      val eventLog = replacedTestThen(
        """
        suite "ConfigSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """
      )

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the count line counts the scope and the one test the last structure holds")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ConfigSpec:{reset}
          |    {red}samples.ConfigSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}
          |    {red}(1 scope, 1 test not run){reset}""".stripExpectedMargin
    }
  }
}

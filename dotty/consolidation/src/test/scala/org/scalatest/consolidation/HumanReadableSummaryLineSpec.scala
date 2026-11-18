package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableSummaryLineSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  Feature("The human-readable output ends with one unindented summary line, each part in the colour of a line of its kind and every other character cyan") {

    Scenario("A run with a test of every outcome, an aborted suite and a suite not run") {
      Given("an event log of a suite whose tests succeed, fail, are ignored, pending and cancelled, a suite that aborts, and a suite that does not start")
      val eventLog =
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "pops", label "- pops"
            test "peeks", label "- peeks", ignored
            test "grows", label "- grows"
            test "shrinks", label "- shrinks"
        structure
          suite "QueueFunSuite", class "samples.QueueFunSuite"
            test "enqueues", label "- enqueues"
            test "dequeues", label "- dequeues"
        structure
          suite "HeapFunSuite", class "samples.HeapFunSuite"
            test "sifts", label "- sifts"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        test "pushes": TestSucceeded
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "peeks": TestIgnored
        test "grows": TestStarting
        test "grows": TestPending
        test "shrinks": TestStarting
        test "shrinks": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack")
        suite "StackFunSuite": SuiteCompleted
        suite "QueueFunSuite": SuiteStarting
        test "enqueues": TestStarting
        test "enqueues": TestSucceeded
        suite "QueueFunSuite": SuiteAborted(throwable: java.lang.IllegalStateException "queue closed")
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedOutputFrom(eventLog)

      Then("the summary line, unindented, follows the lines for `core.test`")
      output.lines.head shouldBe "{cyan}[core.test]{reset}"
      output.lines.init.tail.foreach(_ should startWith(" "))
      output.summaryLine shouldBe
        "{cyan}Suites: 1 of 3 completed, {reset}{red}1 aborted{reset}" +
          "{cyan}. Tests: 6 of 8 run: {reset}{green}succeeded 3{reset}{cyan}, {reset}{red}failed 1{reset}" +
          "{cyan}, {reset}{yellow}canceled 1{reset}{cyan}, {reset}{yellow}ignored 1{reset}{cyan}, {reset}{yellow}pending 1{reset}"
    }

    Scenario("A run that aborts while a suite and a test are open") {
      Given("an event log of a run that aborts while a suite and one of its tests are open, before its last test starts")
      val eventLog =
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "pops", label "- pops"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        run ended aborted
        """

      When("the library consolidates it")
      Then("the summary line counts the open suite as aborted, and the open and unstarted tests as expected and not run")
      colourMarkedOutputFrom(eventLog).summaryLine shouldBe
        "{cyan}Suites: 0 of 1 completed, {reset}{red}1 aborted{reset}" +
          "{cyan}. Tests: 1 of 3 run: {reset}{green}succeeded 1{reset}{cyan}, {reset}{red}failed 0{reset}" +
          "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 0{reset}{cyan}, {reset}{yellow}pending 0{reset}"
    }

    Scenario("A test that runs twice counts twice") {
      Given("an event log of a test that fails, then runs again and succeeds")
      val eventLog =
        """
        run started
        structure
          suite "RetrySuite", class "samples.RetrySuite"
            test "flaky", label "- flaky"
        suite "RetrySuite": SuiteStarting
        test "flaky": TestStarting
        test "flaky": TestFailed(throwable: java.lang.IllegalStateException "not yet")
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: TestSucceeded
        suite "RetrySuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      Then("the summary line counts each execution of the test")
      colourMarkedOutputFrom(eventLog).summaryLine shouldBe
        "{cyan}Suites: 1 of 1 completed, {reset}{red}0 aborted{reset}" +
          "{cyan}. Tests: 2 of 2 run: {reset}{green}succeeded 1{reset}{cyan}, {reset}{red}failed 1{reset}" +
          "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 0{reset}{cyan}, {reset}{yellow}pending 0{reset}"
    }
  }

  Feature("An attributed suite or test is not counted") {

    Scenario("Two sibling suites sharing a suite ID run in reverse order, each running the test the other registered") {
      Given("an event log of two suites sharing a suite ID, in which each suite's registered test is attributed to the test the other ran")
      val eventLog =
        """
        run started
        structure
          suite "PairSuites", class "samples.PairSuites"
            suite "ModeSuite", class "samples.ModeSuite"
              test "fast", label "- fast"
            suite "ModeSuite", class "samples.ModeSuite"
              test "slow", label "- slow"
        suite "PairSuites": SuiteStarting
        structure under suite "PairSuites"
          suite "ModeSuite" #1, class "samples.ModeSuite"
            test "slow", label "- slow"
        test "slow" #2: TestStarting
        test "slow" #2: TestSucceeded
        structure under suite "PairSuites"
          suite "ModeSuite" #2, class "samples.ModeSuite"
            test "fast", label "- fast"
        test "fast" #2: TestStarting
        test "fast" #2: TestSucceeded
        suite "PairSuites": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      Then("the summary line counts the two tests that ran, and neither attributed test")
      colourMarkedOutputFrom(eventLog).summaryLine shouldBe
        "{cyan}Suites: 3 of 3 completed, {reset}{red}0 aborted{reset}" +
          "{cyan}. Tests: 2 of 2 run: {reset}{green}succeeded 2{reset}{cyan}, {reset}{red}failed 0{reset}" +
          "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 0{reset}{cyan}, {reset}{yellow}pending 0{reset}"
    }

    Scenario("A suite registered at two places runs at one") {
      Given("an event log of two suites sharing a suite ID, of which only the first runs, so the second and its test are attributed")
      val eventLog =
        """
        run started
        structure
          suite "TwinSuites", class "samples.TwinSuites"
            suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
            suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
        suite "TwinSuites": SuiteStarting
        suite "InnerSuite" #1: SuiteStarting
        scope "A Stack" #1: ScopeOpened
        test "pops" #1: TestStarting
        test "pops" #1: TestSucceeded
        scope "A Stack" #1: ScopeClosed
        suite "InnerSuite" #1: SuiteCompleted
        suite "TwinSuites": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      Then("the summary line counts the suites and the test that ran, and neither the attributed suite nor its test")
      colourMarkedOutputFrom(eventLog).summaryLine shouldBe
        "{cyan}Suites: 2 of 2 completed, {reset}{red}0 aborted{reset}" +
          "{cyan}. Tests: 1 of 1 run: {reset}{green}succeeded 1{reset}{cyan}, {reset}{red}failed 0{reset}" +
          "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 0{reset}{cyan}, {reset}{yellow}pending 0{reset}"
    }
  }

  Feature("A part of the summary line keeps its colour where its count is 0") {

    Scenario("A run whose tests succeed, are ignored or are pending") {
      Given("an event log of a suite whose tests succeed, are ignored or are pending")
      val eventLog =
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "peeks", label "- peeks", ignored
            test "grows", label "- grows"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        test "pushes": TestSucceeded
        test "peeks": TestIgnored
        test "grows": TestStarting
        test "grows": TestPending
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedOutputFrom(eventLog)

      Then("the summary line, unindented, follows the lines for `core.test`, with `0 aborted` and `failed 0` still red")
      output.tree shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFunSuite:{reset}
          |    {green}- is empty{reset}
          |    {green}- pushes{reset}
          |    {yellow}- peeks !!! IGNORED !!!{reset}
          |    {yellow}- grows (pending){reset}""".stripExpectedMargin
      output.summaryLine shouldBe
        "{cyan}Suites: 1 of 1 completed, {reset}{red}0 aborted{reset}" +
          "{cyan}. Tests: 3 of 3 run: {reset}{green}succeeded 2{reset}{cyan}, {reset}{red}failed 0{reset}" +
          "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 1{reset}{cyan}, {reset}{yellow}pending 1{reset}"
    }
  }
}

package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableCollapseSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  Feature("An aborted node whose descendants never started shows them only as a count") {

    Scenario("The count line counts the suites, scopes and tests at every depth below the root") {
      Given("an event log of a suite that aborts before any of its 2 nested suites, 1 scope and 3 tests starts")
      val eventLog =
        """
        run started
        structure
          suite "BrokenFixtureSpec", class "samples.BrokenFixtureSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
            test "write", label "- write"
            suite "Inner1Spec", class "samples.Inner1Spec"
              test "inner", label "- inner"
            suite "Inner2Spec", class "samples.Inner2Spec"
        suite "BrokenFixtureSpec": SuiteStarting
        suite "BrokenFixtureSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the suite shows its name and abort lines, then `(2 suites, 1 scope, 3 tests not run)`, and no line of a node below it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}BrokenFixtureSpec:{reset}
          |    {red}samples.BrokenFixtureSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}
          |    {red}(2 suites, 1 scope, 3 tests not run){reset}""".stripExpectedMargin
    }

    Scenario("A collapsed root with nothing below it shows no count line") {
      Given("an event log of a suite with no scopes or tests that aborts")
      val eventLog =
        """
        run started
        structure
          suite "EmptySpec", class "samples.EmptySpec"
        suite "EmptySpec": SuiteStarting
        suite "EmptySpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the suite shows its name and abort lines alone")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}EmptySpec:{reset}
          |    {red}samples.EmptySpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }

    Scenario("A scope open when its suite aborted, with no started children, collapses inside a suite that partly ran") {
      Given("an event log of a suite that aborts after its first test, while a scope is open whose tests never started")
      val eventLog =
        """
        run started
        structure
          suite "PartlyRunSpec", class "samples.PartlyRunSpec"
            test "ran", label "- ran"
            scope "A repo", label "A repo"
              test "read", label "- should read"
        suite "PartlyRunSpec": SuiteStarting
        test "ran": TestStarting
        test "ran": TestSucceeded
        scope "A repo": ScopeOpened
        scope "A repo": InfoProvided(message: "connecting")
        suite "PartlyRunSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the scope shows its line, its message, then its count line, and no line of its test")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}PartlyRunSpec:{reset}
          |    {green}- ran{reset}
          |    {green}A repo{reset}{red} *** ABORTED ***{reset}
          |      {green}[info] connecting{reset}
          |      {red}(1 test not run){reset}
          |    {red}samples.PartlyRunSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }

    Scenario("A node that started never collapses into its aborted ancestor") {
      Given("an event log of a suite that aborts while the first of its scope's two tests runs")
      val eventLog =
        """
        run started
        structure
          suite "MidTestSpec", class "samples.MidTestSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
              test "write", label "- should write"
        suite "MidTestSpec": SuiteStarting
        scope "A repo": ScopeOpened
        test "read": TestStarting
        suite "MidTestSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the scope and both tests show their own lines")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}MidTestSpec:{reset}
          |    {green}A repo{reset}{red} *** ABORTED ***{reset}
          |      {red}- should read *** ABORTED ***{reset}
          |      {red}- should write *** NOT RUN ***{reset}
          |    {red}samples.MidTestSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }
  }
}

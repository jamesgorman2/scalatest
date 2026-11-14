package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableTreeSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  Feature("The tree of the human-readable output") {

    Scenario("Nested suites sit under the suite that nests them") {
      Given("an event log of a suite nesting two suites, each with one test that succeeds")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
            suite "Inner2Suite", class "samples.Inner2Suite"
              test "inner two", label "- inner two"
        suite "OuterSuites": SuiteStarting
        suite "Inner1Suite": SuiteStarting
        test "inner one": TestStarting
        test "inner one": TestSucceeded
        suite "Inner1Suite": SuiteCompleted
        suite "Inner2Suite": SuiteStarting
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "Inner2Suite": SuiteCompleted
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each nested suite and its test sit under the suite that nests it, under the test run label")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuites:{reset}
          |    {green}Inner1Suite:{reset}
          |      {green}- inner one{reset}
          |    {green}Inner2Suite:{reset}
          |      {green}- inner two{reset}""".stripMargin
    }

    Scenario("A scope's or test's line shows its label, with or without a leading `- `, never its name") {
      Given("an event log of a WordSpec and a FeatureSpec, whose labels differ from their names")
      val eventLog =
        """
        run started
        structure
          suite "SetWordSpec", class "samples.SetWordSpec"
            scope "A Set", label "A Set"
              scope "empty", label "when empty"
                test "have size 0", label "- should have size 0"
        suite "SetWordSpec": SuiteStarting
        scope "A Set": ScopeOpened
        scope "empty": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": TestSucceeded
        scope "empty": ScopeClosed
        scope "A Set": ScopeClosed
        suite "SetWordSpec": SuiteCompleted
        structure
          suite "ShopFeatureSpec", class "samples.ShopFeatureSpec"
            scope "Checkout", label "Feature: Checkout"
              test "paying by card", label "Scenario: paying by card"
        suite "ShopFeatureSpec": SuiteStarting
        scope "Checkout": ScopeOpened
        test "paying by card": TestStarting
        test "paying by card": TestSucceeded
        scope "Checkout": ScopeClosed
        suite "ShopFeatureSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each scope and test shows its label")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SetWordSpec:{reset}
          |    {green}A Set{reset}
          |      {green}when empty{reset}
          |        {green}- should have size 0{reset}
          |  {green}ShopFeatureSpec:{reset}
          |    {green}Feature: Checkout{reset}
          |      {green}Scenario: paying by card{reset}""".stripMargin
    }

    Scenario("A test run label heads its run's suites, unindented and in cyan") {
      Given("an event log of one suite whose one property succeeds")
      val eventLog =
        """
        run started
        structure
          suite "SqrtPropSpec", class "samples.SqrtPropSpec"
            test "sqrt of a square is the root", label "- sqrt of a square is the root"
        suite "SqrtPropSpec": SuiteStarting
        test "sqrt of a square is the root": TestStarting
        test "sqrt of a square is the root": TestSucceeded
        suite "SqrtPropSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it under the test run label `core.test`")
      val output = consolidated(eventLog)

      Then("the first line is the label, with the suite under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripMargin
    }
  }

  Feature("The order of suites, scopes and tests") {

    Scenario("A label's top-level suites appear in ascending order by name, whatever their classes and the order they ran in") {
      Given("an event log of suites run in descending order by name, whose classes sort in the order they ran")
      val eventLog =
        """
        run started
        structure
          suite "ZetaSuite", class "a.ZetaSuite"
            test "zeta", label "- zeta"
        suite "ZetaSuite": SuiteStarting
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        suite "ZetaSuite": SuiteCompleted
        structure
          suite "MidSuite", class "m.MidSuite"
            test "mid", label "- mid"
        suite "MidSuite": SuiteStarting
        test "mid": TestStarting
        test "mid": TestSucceeded
        suite "MidSuite": SuiteCompleted
        structure
          suite "AlphaSuite", class "z.AlphaSuite"
            test "alpha", label "- alpha"
        suite "AlphaSuite": SuiteStarting
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        suite "AlphaSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the suites appear in ascending order by name")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}AlphaSuite:{reset}
          |    {green}- alpha{reset}
          |  {green}MidSuite:{reset}
          |    {green}- mid{reset}
          |  {green}ZetaSuite:{reset}
          |    {green}- zeta{reset}""".stripMargin
    }

    Scenario("Below a top-level suite, a node's children appear in declared order, whatever their names and the order they ran in") {
      Given("an event log of scopes and tests declared out of name order and run out of declared order")
      val eventLog =
        """
        run started
        structure
          suite "OrderFunSpec", class "samples.OrderFunSpec"
            scope "B", label "B"
              test "z", label "- z"
              test "a", label "- a"
            scope "A", label "A"
              test "m", label "- m"
        suite "OrderFunSpec": SuiteStarting
        scope "B": ScopeOpened
        test "a": TestStarting
        test "a": TestSucceeded
        test "z": TestStarting
        test "z": TestSucceeded
        scope "B": ScopeClosed
        scope "A": ScopeOpened
        test "m": TestStarting
        test "m": TestSucceeded
        scope "A": ScopeClosed
        suite "OrderFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the scopes and tests appear in declared order")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OrderFunSpec:{reset}
          |    {green}B{reset}
          |      {green}- z{reset}
          |      {green}- a{reset}
          |    {green}A{reset}
          |      {green}- m{reset}""".stripMargin
    }
  }

  Feature("Nodes that share a name") {

    Scenario("Two scopes that share a name are not merged") {
      Given("an event log of a suite with two scopes of one name, each with its own test")
      val eventLog =
        """
        run started
        structure
          suite "DupFunSpec", class "samples.DupFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "DupFunSpec": SuiteStarting
        scope "A Stack" #1: ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack" #1: ScopeClosed
        scope "A Stack" #2: ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack" #2: ScopeClosed
        suite "DupFunSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("each scope shows its own test")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}DupFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- is empty{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}""".stripMargin
    }

    Scenario("Two suites that share a name are not merged") {
      Given("an event log of two suites of one name and different classes, each with its own test")
      val eventLog =
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the output holds two `SameSuite:` lines, one with `- in a` and the other with `- in b`")
      val suiteA = "  {green}SameSuite:{reset}\n    {green}- in a{reset}"
      val suiteB = "  {green}SameSuite:{reset}\n    {green}- in b{reset}"
      output should (equal(s"{cyan}[core.test]{reset}\n$suiteA\n$suiteB") or equal(s"{cyan}[core.test]{reset}\n$suiteB\n$suiteA"))
    }
  }
}

package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class HumanReadableTreeSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns {

  Feature("The tree of the consolidation library's human-readable output") {

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
      val output = colourMarkedTreeFrom(eventLog)

      Then("each nested suite and its test sit under the suite that nests it, under the module's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuites:{reset}
          |    {green}Inner1Suite:{reset}
          |      {green}- inner one{reset}
          |    {green}Inner2Suite:{reset}
          |      {green}- inner two{reset}""".stripExpectedMargin
    }

    Scenario("A suite a test ran sits under the test's line") {
      Given("an event log of a test that ran a helper suite, followed by a test that ran none")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "uses a helper", label "- uses a helper"
            test "stands alone", label "- stands alone"
        suite "OuterSuite": SuiteStarting
        test "uses a helper": TestStarting
        structure under test "uses a helper"
          suite "HelperSuite", class "samples.HelperSuite"
            test "helps", label "- helps"
        suite "HelperSuite": SuiteStarting
        test "helps": TestStarting
        test "helps": TestSucceeded
        suite "HelperSuite": SuiteCompleted
        test "uses a helper": TestSucceeded
        test "stands alone": TestStarting
        test "stands alone": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the helper suite's name line sits under the line of the test that ran it, with its test under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuite:{reset}
          |    {green}- uses a helper{reset}
          |      {green}HelperSuite:{reset}
          |        {green}- helps{reset}
          |    {green}- stands alone{reset}""".stripExpectedMargin
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("each scope and test shows its label")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SetWordSpec:{reset}
          |    {green}A Set{reset}
          |      {green}when empty{reset}
          |        {green}- should have size 0{reset}
          |  {green}ShopFeatureSpec:{reset}
          |    {green}Feature: Checkout{reset}
          |      {green}Scenario: paying by card{reset}""".stripExpectedMargin
    }

    Scenario("A module's line heads its suites, unindented and in cyan") {
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

      When("the library consolidates it as a run of the module `core.test`")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the first line is the module's line, with the suite under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }
  }

  Feature("The order of suites, scopes and tests") {

    Scenario("A run's top-level suites appear in ascending lexicographic order by name, whatever their classes and the order they ran in") {
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("the suites appear in ascending lexicographic order by name")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}AlphaSuite:{reset}
          |    {green}- alpha{reset}
          |  {green}MidSuite:{reset}
          |    {green}- mid{reset}
          |  {green}ZetaSuite:{reset}
          |    {green}- zeta{reset}""".stripExpectedMargin
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("the scopes and tests appear in declared order")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OrderFunSpec:{reset}
          |    {green}B{reset}
          |      {green}- z{reset}
          |      {green}- a{reset}
          |    {green}A{reset}
          |      {green}- m{reset}""".stripExpectedMargin
    }

    Scenario("The suites that a test ran appear in ascending lexicographic order by name, whatever the order they ran in") {
      Given("an event log of a test that ran two helper suites in descending order by name")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "uses helpers", label "- uses helpers"
        suite "OuterSuite": SuiteStarting
        test "uses helpers": TestStarting
        structure under test "uses helpers"
          suite "ZetaHelper", class "a.ZetaHelper"
            test "zeta", label "- zeta"
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        structure under test "uses helpers"
          suite "AlphaHelper", class "z.AlphaHelper"
            test "alpha", label "- alpha"
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        test "uses helpers": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the helper suites appear under the test in ascending lexicographic order by name")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuite:{reset}
          |    {green}- uses helpers{reset}
          |      {green}AlphaHelper:{reset}
          |        {green}- alpha{reset}
          |      {green}ZetaHelper:{reset}
          |        {green}- zeta{reset}""".stripExpectedMargin
    }

    Scenario("The suites that a suite ran without declaring them appear after its declared children, in ascending lexicographic order by name") {
      Given("an event log of a suite that ran two undeclared suites in descending order by name before its declared children")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "DeclaredSuite", class "samples.DeclaredSuite"
              test "declared", label "- declared"
            test "own", label "- own"
        suite "OuterSuites": SuiteStarting
        structure under suite "OuterSuites"
          suite "ZetaSuite", class "a.ZetaSuite"
            test "zeta", label "- zeta"
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        structure under suite "OuterSuites"
          suite "AlphaSuite", class "z.AlphaSuite"
            test "alpha", label "- alpha"
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        test "declared": TestStarting
        test "declared": TestSucceeded
        test "own": TestStarting
        test "own": TestSucceeded
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the undeclared suites follow the declared suite and test, in ascending lexicographic order by name")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuites:{reset}
          |    {green}DeclaredSuite:{reset}
          |      {green}- declared{reset}
          |    {green}- own{reset}
          |    {green}AlphaSuite:{reset}
          |      {green}- alpha{reset}
          |    {green}ZetaSuite:{reset}
          |      {green}- zeta{reset}""".stripExpectedMargin
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("each scope shows its own test")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}DupFunSpec:{reset}
          |    {green}A Stack{reset}
          |      {green}- is empty{reset}
          |    {green}A Stack{reset}
          |      {green}- pops{reset}""".stripExpectedMargin
    }

    Scenario("Two top-level suites that share a name are not merged, and appear in the order they started") {
      Given("an event log of two suites of one name and different classes, the suite registered second starting first")
      val eventLog =
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the suite that started first comes first, each with its own test")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in b{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in a{reset}""".stripExpectedMargin
    }

    Scenario("Two executions of a suite are apart where a suite of the same name started between them") {
      Given("an event log of a suite that ran, then a suite of the same name, then the first suite again")
      val eventLog =
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        suite "SameSuite" #1 execution 2: SuiteStarting
        test "in a" execution 2: TestStarting
        test "in a" execution 2: TestSucceeded
        suite "SameSuite" #1 execution 2: SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the executions appear in the order they started")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in a{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in b{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in a{reset}""".stripExpectedMargin
    }

    Scenario("A suite that never started follows the executions of the suites that share its name") {
      Given("an event log of two suites of one name, of which only the suite registered second starts")
      val eventLog =
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("the suite that started comes first")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SameSuite:{reset}
          |    {green}- in b{reset}
          |  {green}SameSuite:{reset}
          |    {red}a.SameSuite *** NOT RUN ***{reset}
          |    {red}(1 test not run){reset}""".stripExpectedMargin
    }

    Scenario("A nested suite that ran twice in one run of its parent sits directly after the last sibling sharing its suite ID") {
      Given("an event log of a suite whose first nested suite ran a second time, before the second nested suite")
      val eventLog =
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
              test "inner more", label "- inner more"
            suite "Inner2Suite", class "samples.Inner2Suite"
              test "inner two", label "- inner two"
        suite "OuterSuites": SuiteStarting
        test "inner one" #1: TestStarting
        test "inner one" #1: TestSucceeded
        test "inner more": TestStarting
        test "inner more": TestSucceeded
        structure under suite "OuterSuites"
          suite "Inner1Suite" #2, class "samples.Inner1Suite"
            test "inner one", label "- inner one"
        test "inner one" #2: TestStarting
        test "inner one" #2: TestSucceeded
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the second run of the first nested suite follows the first, with the tests that the extra suite registers")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuites:{reset}
          |    {green}Inner1Suite:{reset}
          |      {green}- inner one{reset}
          |      {green}- inner more{reset}
          |    {green}Inner1Suite:{reset}
          |      {green}- inner one{reset}
          |    {green}Inner2Suite:{reset}
          |      {green}- inner two{reset}""".stripExpectedMargin
    }
  }
}

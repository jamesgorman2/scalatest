package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableRunSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  import HumanReadableRunSpec.*

  private def stateAfterEnd(parts: TestRunPart*): ConsolidationState = {
    val consolidation = Consolidation.start(new OutputSink, coloured = true)
    addRun(consolidation, parts*).endEventStream()
    consolidation.end()
    consolidation.state
  }

  Feature("A run's block") {

    Scenario("The run's messages come before its captured output") {
      Given("an event log of a completed run with an alert attached to the run, and a stdout line fed before the alert")
      When("the library consolidates it")
      val output = consolidated(runWithAlertAndStdoutLine*)

      Then("the run's block follows the suites, with the alert, then the stdout line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}CalculatorSuite:{reset}
          |    {green}- adds two numbers{reset}
          |  {cyan}Output outside any suite:{reset}
          |    {yellow}[alert] Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.{reset}
          |    [stdout] dividing now""".stripExpectedMargin
    }

    Scenario("Captured lines of both streams keep the one order they were fed") {
      Given("an event log of a run that starts and completes, with stderr, stdout and stderr lines fed in turn")
      val parts = Vector(
        TestRunPart.Events("run started"),
        TestRunPart.CapturedLine(CapturedStream.Stderr, "SLF4J: No SLF4J providers were found."),
        TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
        TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide"),
        TestRunPart.Events("run ended completed")
      )

      When("the library consolidates it")
      val output = consolidated(parts*)

      Then("each line shows its stream and nothing but the line, in the order fed, stderr lines yellow")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Output outside any suite:{reset}
          |    {yellow}[stderr] SLF4J: No SLF4J providers were found.{reset}
          |    [stdout] dividing now
          |    {yellow}[stderr] warn: about to divide{reset}""".stripExpectedMargin
    }

    Scenario("Run events and a DiscoverySuite's events assigned to the run show no line") {
      Given("an event log of a completed run holding run events and a DiscoverySuite's events, all assigned to the run")
      val eventLog =
        """
        run started
        run: RunStarting
        run: DiscoveryStarting
        run: DiscoveryCompleted
        run: SuiteStarting
        structure
          suite "SqrtPropSpec", class "samples.SqrtPropSpec"
            test "sqrt of a square is the root", label "- sqrt of a square is the root"
        suite "SqrtPropSpec": SuiteStarting
        test "sqrt of a square is the root": TestStarting
        test "sqrt of a square is the root": TestSucceeded
        suite "SqrtPropSpec": SuiteCompleted
        run: SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the output holds the suite and no run block")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }

    Scenario("Each run under a label shows its own block after all the label's suites") {
      Given("two runs under one label, each with a suite and a stdout line")
      def run(suite: String, line: String): Vector[TestRunPart] =
        Vector(
          TestRunPart.Events(
            s"""
            run started
            structure
              suite "$suite", class "samples.$suite"
                test "runs", label "- runs"
            suite "$suite": SuiteStarting
            test "runs": TestStarting
            test "runs": TestSucceeded
            suite "$suite": SuiteCompleted
            """
          ),
          TestRunPart.CapturedLine(CapturedStream.Stdout, line),
          TestRunPart.Events("run ended completed")
        )
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, run("ZetaSuite", "from the first run")*).endEventStream()
      addRun(consolidation, run("AlphaSuite", "from the second run")*).endEventStream()

      When("the library consolidates them")
      consolidation.end()

      Then("the label shows both suites, then the first run's block, then the second's")
      output.markedText shouldBe
        """{cyan}[core.test]{reset}
          |  {green}AlphaSuite:{reset}
          |    {green}- runs{reset}
          |  {green}ZetaSuite:{reset}
          |    {green}- runs{reset}
          |  {cyan}Output outside any suite:{reset}
          |    [stdout] from the first run
          |  {cyan}Output outside any suite:{reset}
          |    [stdout] from the second run""".stripExpectedMargin
    }
  }

  Feature("Runs that abort or stop") {

    Scenario("A test's fatal error aborts the run") {
      Given("an event log of a run that aborts while a suite, two scopes and a test are open, with a stderr line captured")
      When("the library consolidates it")
      val output = consolidated(abortedRun*)

      Then("the open nodes are aborted, the nodes that never started are not run, and the block ends with the run's abort")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}AbortingFunSpec:{reset}
          |    {green}A Stack{reset}{red} *** ABORTED ***{reset}
          |      {green}when empty{reset}{red} *** ABORTED ***{reset}
          |        {green}- is empty{reset}
          |        {red}- throws on pop *** ABORTED ***{reset}
          |        {red}- has size 0 *** NOT RUN ***{reset}
          |      {green}when full{reset}{red} *** NOT RUN ***{reset}
          |        {red}(1 test not run){reset}
          |    {red}samples.AbortingFunSpec *** ABORTED ***{reset}
          |  {cyan}Output outside any suite:{reset}
          |    {yellow}[alert] Test still running after 1 minute, 2 seconds: suite name: AbortingFunSpec, test name: A Stack when empty throws on pop.{reset}
          |    {yellow}[stderr] OpenJDK 64-Bit Server VM warning: CodeCache is full.{reset}
          |    {red}*** RUN ABORTED ***{reset}
          |      {red}An exception or error caused a run to abort: Java heap space {reset}
          |      {red}java.lang.OutOfMemoryError: Java heap space{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }

    Scenario("A run stops between two nested suites") {
      Given("an event log of a run that stops after the first of two nested suites")
      When("the library consolidates it")
      val output = consolidated(stoppedRun*)

      Then("the suite that never started is not run, and the block holds the run's stop")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OuterSuites:{reset}
          |    {green}Inner1Suite:{reset}
          |      {green}- inner one{reset}
          |    {green}Inner2Suite:{reset}
          |      {red}samples.Inner2Suite *** NOT RUN ***{reset}
          |      {red}(1 test not run){reset}
          |  {cyan}Output outside any suite:{reset}
          |    {red}*** RUN STOPPED ***{reset}""".stripExpectedMargin
    }

    Scenario("A run that ends aborted without a RunAborted shows its abort alone") {
      Given("an event log of a run that starts and ends aborted")
      When("the library consolidates it")
      val output = consolidated("run started\nrun ended aborted")

      Then("the block holds only the run's abort line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Output outside any suite:{reset}
          |    {red}*** RUN ABORTED ***{reset}""".stripExpectedMargin
    }

    Scenario("A run's outcome comes from its runEnded event alone") {
      Given("an event log of a run that reports RunAborted, then ends completed")
      val eventLog =
        """
        run started
        run: RunAborted(message: "An exception or error caused a run to abort: Java heap space ")
        run ended completed
        """

      When("the library consolidates it")
      val output = consolidated(eventLog)

      Then("the run shows no block")
      output shouldBe "{cyan}[core.test]{reset}"
    }
  }

  Feature("The consolidation state of a run that ends other than completed") {

    Scenario("A run that completes leaves the state ended") {
      Given("the event log and captured output of a completed run")
      When("the library consolidates it")
      Then("the consolidation state is ended")
      stateAfterEnd(runWithAlertAndStdoutLine*) shouldBe ConsolidationState.Ended
    }

    Scenario("A run that aborts leaves the state ended and failed") {
      Given("the event log and captured output of a run that aborts")
      When("the library consolidates it")
      Then("the consolidation state is ended and failed")
      stateAfterEnd(abortedRun*) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A run that stops leaves the state ended and failed") {
      Given("the event log of a run that stops")
      When("the library consolidates it")
      Then("the consolidation state is ended and failed")
      stateAfterEnd(stoppedRun*) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A run whose event stream ends without its runEnded event leaves the state ended and failed") {
      Given("an event log of a run that starts and never ends")
      val consolidation = Consolidation.start(new OutputSink, coloured = true)
      val feed = addRun(consolidation, TestRunPart.Events("run started"))

      When("the caller ends the run's event stream")
      feed.endEventStream()

      Then("the consolidation state is failed at once, and ended and failed after End")
      consolidation.state shouldBe ConsolidationState.OpenFailed
      consolidation.end()
      consolidation.state shouldBe ConsolidationState.EndedFailed
    }

    Scenario("The runEnded event fails the state, not RunAborted or RunStopped") {
      Given("a run fed RunAborted and RunStopped events")
      val consolidation = Consolidation.start(new OutputSink, coloured = true)
      val feed = addRun(consolidation, TestRunPart.Events("run started\nrun: RunAborted\nrun: RunStopped"))
      consolidation.state shouldBe ConsolidationState.Open

      When("the run's runEnded event, ended aborted, is added")
      feed.add(runEvent("run ended aborted"))

      Then("the consolidation state is failed before the operation returns")
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }
  }
}

private object HumanReadableRunSpec {

  val runWithAlertAndStdoutLine: Vector[TestRunPart] = Vector(
    TestRunPart.Events(
      """
      run started
      structure
        suite "CalculatorSuite", class "samples.CalculatorSuite"
          test "adds two numbers", label "- adds two numbers"
      suite "CalculatorSuite": SuiteStarting
      test "adds two numbers": TestStarting
      """
    ),
    TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
    TestRunPart.Events(
      """
      run: AlertProvided(message: "Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.")
      test "adds two numbers": TestSucceeded
      suite "CalculatorSuite": SuiteCompleted
      run ended completed
      """
    )
  )

  val abortedRun: Vector[TestRunPart] = Vector(
    TestRunPart.Events(
      """
      run started
      structure
        suite "AbortingFunSpec", class "samples.AbortingFunSpec"
          scope "A Stack", label "A Stack"
            scope "when empty", label "when empty"
              test "is empty", label "- is empty"
              test "throws on pop", label "- throws on pop"
              test "has size 0", label "- has size 0"
            scope "when full", label "when full"
              test "pops last pushed", label "- pops last pushed"
      suite "AbortingFunSpec": SuiteStarting
      scope "A Stack": ScopeOpened
      scope "when empty": ScopeOpened
      test "is empty": TestStarting
      test "is empty": TestSucceeded
      test "throws on pop": TestStarting
      run: AlertProvided(message: "Test still running after 1 minute, 2 seconds: suite name: AbortingFunSpec, test name: A Stack when empty throws on pop.")
      """
    ),
    TestRunPart.CapturedLine(CapturedStream.Stderr, "OpenJDK 64-Bit Server VM warning: CodeCache is full."),
    TestRunPart.Events(
      """
      run: RunAborted(message: "An exception or error caused a run to abort: Java heap space ", throwable: java.lang.OutOfMemoryError "Java heap space")
      run ended aborted
      """
    )
  )

  val stoppedRun: Vector[TestRunPart] = Vector(
    TestRunPart.Events(
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
      suite "OuterSuites": SuiteCompleted
      run: RunStopped
      run ended stopped
      """
    )
  )
}

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

  Feature("A run's line") {

    Scenario("A captured line fed before one of the run's messages comes before that message") {
      Given("an event log of a completed run with an alert attached to the run")
      And("a stdout line fed before the alert, and a stderr line fed after it")
      When("the library consolidates it")
      val output = consolidated(runWithAlertBetweenCapturedLines*)

      Then("the run's line holds the suite, then the stdout line, the alert and the stderr line, in the order fed")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}CalculatorSuite:{reset}
          |      {green}- adds two numbers{reset}
          |    [stdout] dividing now
          |    {yellow}[alert] Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.{reset}
          |    {yellow}[stderr] warn: slow{reset}""".stripExpectedMargin
    }

    Scenario("Captured stdout and stderr lines keep the order they were fed, wherever they are fed among the events") {
      Given("an event log holding `run started`, then `run ended completed`, consolidated as a run of the module `core.test`")
      And("stderr, stdout and stderr lines captured, fed in that order at each combination of points among the event log's events")
      val runs = capturedLinesAtEveryPoint(
        "run started\nrun ended completed",
        Vector(
          TestRunPart.CapturedLine(CapturedStream.Stderr, "SLF4J: No SLF4J providers were found."),
          TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide")
        )
      )
      runs should have size 10

      When("the library consolidates each")
      val outputs = runs.map(parts => consolidated(parts*))

      Then("each run's line holds each line's stream and text, in the order fed, with stderr lines yellow")
      all(outputs) shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {yellow}[stderr] SLF4J: No SLF4J providers were found.{reset}
          |    [stdout] dividing now
          |    {yellow}[stderr] warn: about to divide{reset}""".stripExpectedMargin
    }

    Scenario("A completed run with no output attached shows no line, so its suites sit under the module's line") {
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

      Then("the suite sits directly under the module's line, and the output holds no run's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }

    Scenario("Each run of a module shows its own suites and output under its own line, numbered in the order the runs were added") {
      Given("two runs of one module, each with a suite and a stdout line, the first run's suite sorting after the second's")
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

      Then("the module shows Run 1: with the first run's suite and line, then Run 2: with the second's")
      output.markedText shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}ZetaSuite:{reset}
          |      {green}- runs{reset}
          |    [stdout] from the first run
          |  {cyan}Run 2:{reset}
          |    {green}AlphaSuite:{reset}
          |      {green}- runs{reset}
          |    [stdout] from the second run""".stripExpectedMargin
    }

    Scenario("A run keeps its number when an earlier run of its module shows no line") {
      Given("two runs of one module, the first completed with no output, the second with a stdout line")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, TestRunPart.Events("run started\nrun ended completed")).endEventStream()
      addRun(
        consolidation,
        TestRunPart.Events("run started"),
        TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
        TestRunPart.Events("run ended completed")
      ).endEventStream()

      When("the library consolidates them")
      consolidation.end()

      Then("the second run's line is Run 2:")
      output.markedText shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 2:{reset}
          |    [stdout] dividing now""".stripExpectedMargin
    }
  }

  Feature("Runs that abort or stop") {

    Scenario("A test's fatal error aborts the run") {
      Given("an event log of a run that aborts while a suite, two scopes and a test are open, consolidated as a run of the module `core.test`")
      And("a captured stderr line, fed after the alert attached to the run")
      When("the library consolidates it")
      val output = consolidated(abortedRun*)

      Then("the open nodes are aborted, the nodes that never started are not run, and the run's abort comes last under the run's line")
      And("the abort's message keeps its trailing space")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}AbortingFunSpec:{reset}
          |      {green}A Stack{reset}{red} *** ABORTED ***{reset}
          |        {green}when empty{reset}{red} *** ABORTED ***{reset}
          |          {green}- is empty{reset}
          |          {red}- throws on pop *** ABORTED ***{reset}
          |          {red}- has size 0 *** NOT RUN ***{reset}
          |        {green}when full{reset}{red} *** NOT RUN ***{reset}
          |          {red}(1 test not run){reset}
          |      {red}samples.AbortingFunSpec *** ABORTED ***{reset}
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

      Then("the suite that never started is not run, and the run's stop comes last under the run's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}OuterSuites:{reset}
          |      {green}Inner1Suite:{reset}
          |        {green}- inner one{reset}
          |      {green}Inner2Suite:{reset}
          |        {red}samples.Inner2Suite *** NOT RUN ***{reset}
          |        {red}(1 test not run){reset}
          |    {red}*** RUN STOPPED ***{reset}""".stripExpectedMargin
    }

    Scenario("A run that ends aborted without a RunAborted shows only its abort line under the run's line") {
      Given("an event log of a run that starts and ends aborted")
      When("the library consolidates it")
      val output = consolidated("run started\nrun ended aborted")

      Then("the run's line holds only the run's abort line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
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

      Then("the run shows no line")
      output shouldBe "{cyan}[core.test]{reset}"
    }
  }

  Feature("The root") {

    def consolidationOfSqrtPropSpec(output: OutputSink): Consolidation = {
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, TestRunPart.Events(sqrtPropSpecEventLog)).endEventStream()
      consolidation
    }

    Scenario("A runner that is not a run writes a line") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("the root's captured output is the stdout line `discovering tests`")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)
      consolidation.addRootOutput(CapturedLine.Stdout("discovering tests"))

      When("the library consolidates it")
      consolidation.end()

      Then("the root's line heads the module's lines, then the root's captured line")
      output.markedText shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {green}SqrtPropSpec:{reset}
          |      {green}- sqrt of a square is the root{reset}
          |  [stdout] discovering tests""".stripExpectedMargin

      And("the consolidation state the library gives is ended")
      consolidation.state shouldBe ConsolidationState.Ended
    }

    Scenario("A runner that is not a run fails") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)

      And("a failure attached to the root whose message is `test discovery failed`")
      consolidation.addRootFailure("test discovery failed")

      Then("the consolidation state is failed before the operation returns")
      consolidation.state shouldBe ConsolidationState.OpenFailed

      When("the library consolidates it")
      consolidation.end()

      Then("the root's line heads the module's lines, then the root's abort with the failure's message under it")
      output.markedText shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {green}SqrtPropSpec:{reset}
          |      {green}- sqrt of a square is the root{reset}
          |  {red}*** CONSOLIDATED TESTS ABORTED ***{reset}
          |    {red}test discovery failed{reset}""".stripExpectedMargin

      And("the consolidation state the library gives is ended, failed")
      consolidation.state shouldBe ConsolidationState.EndedFailed
    }

    Scenario("The root's captured lines and failures keep the order they were attached, after the lines for each module") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("a failure, a stderr line, a stdout line and a second failure attached to the root, in that order")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)
      consolidation.addRootFailure("first failure")
      consolidation.addRootOutput(CapturedLine.Stderr("warn: slow discovery"))
      consolidation.addRootOutput(CapturedLine.Stdout("discovering tests"))
      consolidation.addRootFailure("second failure")

      When("the library consolidates it")
      consolidation.end()

      Then("the root's captured lines come in the order attached, then its abort with each failure's message in the order attached")
      output.markedText shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {green}SqrtPropSpec:{reset}
          |      {green}- sqrt of a square is the root{reset}
          |  {yellow}[stderr] warn: slow discovery{reset}
          |  [stdout] discovering tests
          |  {red}*** CONSOLIDATED TESTS ABORTED ***{reset}
          |    {red}first failure{reset}
          |    {red}second failure{reset}""".stripExpectedMargin
    }

    Scenario("A run's line sits one level deeper under the root's line") {
      Given("an event log of a run that ends aborted, consolidated as a run of the module `core.test`")
      And("the stdout line `discovering tests` attached to the root")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, TestRunPart.Events("run started\nrun ended aborted")).endEventStream()
      consolidation.addRootOutput(CapturedLine.Stdout("discovering tests"))

      When("the library consolidates it")
      consolidation.end()

      Then("the run's line and its abort sit under the module's line, itself under the root's line")
      output.markedText shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {cyan}Run 1:{reset}
          |      {red}*** RUN ABORTED ***{reset}
          |  [stdout] discovering tests""".stripExpectedMargin
    }

    Scenario("Nothing attached to the root leaves the output without the root's line") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`, with nothing attached to the root")
      When("the library consolidates it")
      val output = consolidated(sqrtPropSpecEventLog)

      Then("the module's line is unindented and the output holds no root's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }
  }

  Feature("The consolidation state of a run that ends") {

    Scenario("A run that ends completed") {
      Given("an event log of a run that ends completed, with an alert attached to the run between a stdout and a stderr line")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended")
      stateAfterEnd(runWithAlertBetweenCapturedLines*) shouldBe ConsolidationState.Ended
    }

    Scenario("A run that ends aborted") {
      Given("an event log of a run that ends aborted, with a stderr line")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended, failed")
      stateAfterEnd(abortedRun*) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A run that ends stopped") {
      Given("an event log of a run that ends stopped")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended, failed")
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

  val sqrtPropSpecEventLog: String =
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

  val runWithAlertBetweenCapturedLines: Vector[TestRunPart] = Vector(
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
      """
    ),
    TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: slow"),
    TestRunPart.Events(
      """
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

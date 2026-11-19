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
      val output = colourMarkedTreeFrom(runWithAlertBetweenCapturedLines*)

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
      val runs = interleavingsOf(
        "run started\nrun ended completed",
        Vector(
          TestRunPart.CapturedLine(CapturedStream.Stderr, "SLF4J: No SLF4J providers were found."),
          TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide")
        )
      )
      runs should have size 10

      When("the library consolidates each")
      val outputs = runs.map(parts => colourMarkedTreeFrom(parts*))

      Then("each run's line holds each line's stream and text, in the order fed, with stderr lines yellow")
      all(outputs) shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {yellow}[stderr] SLF4J: No SLF4J providers were found.{reset}
          |    [stdout] dividing now
          |    {yellow}[stderr] warn: about to divide{reset}""".stripExpectedMargin
    }

    Scenario("A captured line's terminator adds no line") {
      Given("an event log holding `run started`, then `run ended completed`, consolidated as a run of the module `core.test`")
      And("a stdout line captured with its `\\n` and a stderr line captured with its `\\r\\n`")
      val parts = Vector(
        TestRunPart.Events("run started"),
        TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now\n"),
        TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide\r\n"),
        TestRunPart.Events("run ended completed")
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(parts*)

      Then("each line is shown without its terminator")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    [stdout] dividing now
          |    {yellow}[stderr] warn: about to divide{reset}""".stripExpectedMargin
    }

    Scenario("Captured lines sit after the lines for the run's suites, wherever they are fed among the events") {
      Given("an event log of a completed run of a suite with one test, consolidated as a run of the module `core.test`")
      And("a stdout line and a stderr line captured, fed in that order at each combination of points among the event log's events")
      val runs = interleavingsOf(
        calculatorSuiteEventLog,
        Vector(
          TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: slow")
        )
      )
      runs should have size 36

      When("the library consolidates each")
      val outputs = runs.map(parts => colourMarkedTreeFrom(parts*))

      Then("each run's line holds the suite's lines, then the captured lines in the order fed")
      all(outputs) shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}CalculatorSuite:{reset}
          |      {green}- adds two numbers{reset}
          |    [stdout] dividing now
          |    {yellow}[stderr] warn: slow{reset}""".stripExpectedMargin
    }

    Scenario("A run's messages sit after the lines for its suites: info, note and markup in green, alerts in yellow") {
      Given("an event log of a completed run that sends an info, a note, an alert and a markup message, before and while its suite runs")
      val eventLog =
        """
        run started
        run: InfoProvided(message: "discovered 1 suite")
        structure
          suite "CalculatorSuite", class "samples.CalculatorSuite"
            test "adds two numbers", label "- adds two numbers"
        suite "CalculatorSuite": SuiteStarting
        run: NoteProvided(message: "using 4 threads")
        test "adds two numbers": TestStarting
        run: AlertProvided(message: "Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.")
        test "adds two numbers": TestSucceeded
        run: MarkupProvided(text: "*all added*")
        suite "CalculatorSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the run's line holds the suite's lines, then each message marked with its kind, in the order sent")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}CalculatorSuite:{reset}
          |      {green}- adds two numbers{reset}
          |    {green}[info] discovered 1 suite{reset}
          |    {green}[note] using 4 threads{reset}
          |    {yellow}[alert] Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.{reset}
          |    {green}[markup] *all added*{reset}""".stripExpectedMargin
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
      val output = colourMarkedTreeFrom(eventLog)

      Then("the suite sits directly under the module's line, and the output holds no run's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }

    def runWithLine(suite: String, line: String): Vector[TestRunPart] =
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

    Scenario("Each run given no worker shows its own suites and output under its own line, numbered in the order the runs were added") {
      Given("two runs of one module, neither given a worker, each with a suite and a stdout line, the first run's suite sorting after the second's")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, runWithLine("ZetaSuite", "from the first run")*).endEventStream()
      addRun(consolidation, runWithLine("AlphaSuite", "from the second run")*).endEventStream()

      When("the library consolidates them")
      consolidation.end()

      Then("the module shows Run 1: with the first run's suite and line, then Run 2: with the second's")
      output.colourMarkedTree shouldBe
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

    Scenario("A module's runs are numbered by fork group, then worker index, whatever order they were added in") {
      Given("three runs, each with a suite and a stdout line, added with fork group and worker index (1, 0), then (0, 1), then (0, 0)")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRunInWorker(consolidation, 1, 0, runWithLine("FirstAdded", "from fork group 1, worker 0")*).endEventStream()
      addRunInWorker(consolidation, 0, 1, runWithLine("SecondAdded", "from fork group 0, worker 1")*).endEventStream()
      addRunInWorker(consolidation, 0, 0, runWithLine("ThirdAdded", "from fork group 0, worker 0")*).endEventStream()

      When("the library consolidates them")
      consolidation.end()

      Then("the module shows Run 1: for the last run added, Run 2: for the second and Run 3: for the first")
      output.colourMarkedTree shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {green}ThirdAdded:{reset}
          |      {green}- runs{reset}
          |    [stdout] from fork group 0, worker 0
          |  {cyan}Run 2:{reset}
          |    {green}SecondAdded:{reset}
          |      {green}- runs{reset}
          |    [stdout] from fork group 0, worker 1
          |  {cyan}Run 3:{reset}
          |    {green}FirstAdded:{reset}
          |      {green}- runs{reset}
          |    [stdout] from fork group 1, worker 0""".stripExpectedMargin
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
      output.colourMarkedTree shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 2:{reset}
          |    [stdout] dividing now""".stripExpectedMargin
    }
  }

  Feature("Runs that abort or stop") {

    Scenario("A run aborted by a test's fatal error shows its open nodes aborted, and its abort last under the run's line") {
      Given("an event log of a run that aborts while a suite, two scopes and a test are open, consolidated as a run of the module `core.test`")
      And("a captured stderr line, fed after the alert attached to the run")
      When("the library consolidates it")
      val output = colourMarkedTreeFrom(abortedRun*)

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

    Scenario("A run that stops between two nested suites shows the second as not run, and its stop last under the run's line") {
      Given("an event log of a run that stops after the first of two nested suites")
      When("the library consolidates it")
      val output = colourMarkedTreeFrom(stoppedRun*)

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
      val output = colourMarkedTreeFrom("run started\nrun ended aborted")

      Then("the run's line holds only the run's abort line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {red}*** RUN ABORTED ***{reset}""".stripExpectedMargin
    }

    Scenario("A run that reports RunAborted twice shows the first abort's message and throwable") {
      Given("an event log of a run that reports two RunAborted events, each with its own message and throwable, then ends aborted")
      val eventLog =
        """
        run started
        run: RunAborted(message: "An exception or error caused a run to abort: Java heap space ", throwable: java.lang.OutOfMemoryError "Java heap space")
        run: RunAborted(message: "An exception or error caused a run to abort: Metaspace ", throwable: java.lang.OutOfMemoryError "Metaspace")
        run ended aborted
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the run's abort line holds the first RunAborted's message and throwable, and nothing of the second")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {red}*** RUN ABORTED ***{reset}
          |      {red}An exception or error caused a run to abort: Java heap space {reset}
          |      {red}java.lang.OutOfMemoryError: Java heap space{reset}
          |        {red}...{reset}""".stripExpectedMargin
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
      val output = colourMarkedTreeFrom(eventLog)

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

    Scenario("A line captured from a runner that is not a run sits under the root's line, after the module's lines") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("the root's captured output is the stdout line `discovering tests`")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)
      consolidation.addRootOutput(CapturedLine.Stdout("discovering tests"))

      When("the library consolidates it")
      consolidation.end()

      Then("the root's line heads the module's lines, then the root's captured line")
      output.colourMarkedTree shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {green}SqrtPropSpec:{reset}
          |      {green}- sqrt of a square is the root{reset}
          |  [stdout] discovering tests""".stripExpectedMargin

      And("the consolidation state the library gives is ended")
      consolidation.state shouldBe ConsolidationState.Ended
    }

    Scenario("A failure of a runner that is not a run fails the consolidation, and sits under the root's abort line") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)

      And("a failure attached to the root whose message is `test discovery failed`")
      consolidation.addRootFailure("test discovery failed")

      And("the consolidation state is already failed")
      consolidation.state shouldBe ConsolidationState.OpenFailed

      When("the library consolidates it")
      consolidation.end()

      Then("the root's line heads the module's lines, then the root's abort with the failure's message under it")
      output.colourMarkedTree shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {green}SqrtPropSpec:{reset}
          |      {green}- sqrt of a square is the root{reset}
          |  {red}*** CONSOLIDATED TESTS ABORTED ***{reset}
          |    {red}test discovery failed{reset}""".stripExpectedMargin

      And("the consolidation state the library gives is ended, failed")
      consolidation.state shouldBe ConsolidationState.EndedFailed
    }

    Scenario("After the lines for each module, the root's captured lines then its failures, each in the order attached") {
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
      output.colourMarkedTree shouldBe
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
      output.colourMarkedTree shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    {cyan}Run 1:{reset}
          |      {red}*** RUN ABORTED ***{reset}
          |  [stdout] discovering tests""".stripExpectedMargin
    }

    Scenario("Nothing attached to the root leaves the output without the root's line") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`, with nothing attached to the root")
      When("the library consolidates it")
      val output = colourMarkedTreeFrom(sqrtPropSpecEventLog)

      Then("the module's line is unindented and the output holds no root's line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }

    Scenario("A module timed but given no run shows its line alone, among the other modules in order by name") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("the module `app.test` timed but given no run")
      val output = new OutputSink
      val consolidation = consolidationOfSqrtPropSpec(output)
      consolidation.timeModule(TestRunLabel("app.test"), None, None)

      When("the library consolidates it")
      consolidation.end()

      Then("the line of `app.test` comes first, with nothing under it")
      output.colourMarkedTree shouldBe
        """{cyan}[app.test]{reset}
          |{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }
  }

  Feature("The consolidation state of a run that ends") {

    Scenario("A run that ends completed leaves the state ended") {
      Given("an event log of a run that ends completed, with an alert attached to the run between a stdout and a stderr line")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended")
      stateAfterEnd(runWithAlertBetweenCapturedLines*) shouldBe ConsolidationState.Ended
    }

    Scenario("A run that ends aborted leaves the state ended and failed") {
      Given("an event log of a run that ends aborted, with a stderr line")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended, failed")
      stateAfterEnd(abortedRun*) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A run that ends stopped leaves the state ended and failed") {
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
      feed.add("run ended aborted".toRunEvent)

      Then("the consolidation state is failed before the operation returns")
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }

    Scenario("A test with no events and no test to attribute it to shows as not run, and fails the state") {
      Given("an event log of a run that ends completed, in which SkipSuite's test \"skipped\" has no event")
      val parts = TestRunPart.Events(skippedTestEventLog)

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(parts)

      Then("the test shows as not run")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SkipSuite:{reset}
          |    {green}- runs{reset}
          |    {red}- skipped *** NOT RUN ***{reset}""".stripExpectedMargin

      And("the consolidation state the library gives is ended, failed")
      stateAfterEnd(parts) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A test that a later structure drops before it starts, with no test to attribute it to, fails the state") {
      Given("an event log of a run that ends completed, whose further structure drops the test \"local\" before it starts")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended, failed")
      stateAfterEnd(TestRunPart.Events(droppedTestEventLog)) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A suite at two places that runs at only one, with the other place's tests attributed to it, leaves the state ended") {
      Given("an event log of a run that ends completed, in which the second InnerSuite's test is attributed to the first's")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended")
      stateAfterEnd(TestRunPart.Events(twinSuitesEventLog)) shouldBe ConsolidationState.Ended
    }

    Scenario("A suite, scope and test still open when a run completes show as aborted and fail the state") {
      Given("an event log of a run that ends completed while a suite, a scope in it and a test in that scope are open")
      val parts = TestRunPart.Events(openNodesEventLog)

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(parts)

      Then("the suite, the scope and the test show as aborted")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}OpenSpec:{reset}
          |    {green}A Stack{reset}{red} *** ABORTED ***{reset}
          |      {green}- is empty{reset}
          |      {red}- throws on pop *** ABORTED ***{reset}
          |    {red}samples.OpenSpec *** ABORTED ***{reset}""".stripExpectedMargin

      And("the consolidation state the library gives is ended, failed")
      stateAfterEnd(parts) shouldBe ConsolidationState.EndedFailed
    }

    Scenario("A suite run by a test sends no start event, so it is not aborted in a run that ends completed") {
      Given("an event log of a run that ends completed, in which a test ran a helper suite that sent no SuiteStarting")
      When("the library consolidates it")
      Then("the consolidation state the library gives is ended")
      stateAfterEnd(TestRunPart.Events(helperSuiteEventLog)) shouldBe ConsolidationState.Ended
    }
  }
}

private object HumanReadableRunSpec {

  val skippedTestEventLog: String =
    """
    run started
    structure
      suite "SkipSuite", class "samples.SkipSuite"
        test "runs", label "- runs"
        test "skipped", label "- skipped"
    suite "SkipSuite": SuiteStarting
    test "runs": TestStarting
    test "runs": TestSucceeded
    suite "SkipSuite": SuiteCompleted
    run ended completed
    """

  val droppedTestEventLog: String =
    """
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
    scope "A config": ScopeOpened
    test "remote": TestStarting
    test "remote": TestSucceeded
    scope "A config": ScopeClosed
    suite "ConfigSpec": SuiteCompleted
    run ended completed
    """

  val twinSuitesEventLog: String =
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

  val openNodesEventLog: String =
    """
    run started
    structure
      suite "OpenSpec", class "samples.OpenSpec"
        scope "A Stack", label "A Stack"
          test "is empty", label "- is empty"
          test "throws on pop", label "- throws on pop"
    suite "OpenSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    test "is empty": TestStarting
    test "is empty": TestSucceeded
    test "throws on pop": TestStarting
    run ended completed
    """

  val helperSuiteEventLog: String =
    """
    run started
    structure
      suite "OuterSuite", class "samples.OuterSuite"
        test "uses a helper", label "- uses a helper"
    suite "OuterSuite": SuiteStarting
    test "uses a helper": TestStarting
    structure under test "uses a helper"
      suite "HelperSuite", class "samples.HelperSuite"
        test "helps", label "- helps"
    test "helps": TestStarting
    test "helps": TestSucceeded
    test "uses a helper": TestSucceeded
    suite "OuterSuite": SuiteCompleted
    run ended completed
    """

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

  val calculatorSuiteEventLog: String =
    """
    run started
    structure
      suite "CalculatorSuite", class "samples.CalculatorSuite"
        test "adds two numbers", label "- adds two numbers"
    suite "CalculatorSuite": SuiteStarting
    test "adds two numbers": TestStarting
    test "adds two numbers": TestSucceeded
    suite "CalculatorSuite": SuiteCompleted
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

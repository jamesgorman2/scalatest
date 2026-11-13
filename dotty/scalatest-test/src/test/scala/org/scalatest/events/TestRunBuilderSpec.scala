package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestRunBuilderSpec extends AnyWordSpec with Matchers {

  private val stackSuite =
    """
    structure
      suite "StackSuite", class "samples.StackSuite"
        scope "A Stack", label "A Stack"
          test "pops", label "- pops"
    """

  private def joined(blocks: String*): String = {
    blocks.map { block =>
      val lines = block.split('\n').filter(_.trim.nonEmpty)
      val indentation = lines.map(_.takeWhile(_ == ' ').length).min
      lines.map(_.drop(indentation)).mkString("\n")
    }.mkString("\n")
  }

  private def built(parts: TestRunPart*): BuiltRun = {
    TestRunBuilder.build(parts*) match {
      case Right(run) => run
      case Left(reason) => fail(s"the run was not built: $reason")
    }
  }

  private def eventsOf(notation: String): Vector[TestEventLogEvent] = built(TestRunPart.Events(notation)).events.map(_.event)

  private def rejection(notation: String): Option[String] = TestRunBuilder.build(TestRunPart.Events(notation)).left.toOption

  private def structuresOf(notation: String): Vector[IndexedSeq[StructureNode]] =
    eventsOf(notation).collect { case TestEventLogEvent.Structure(nodes) => nodes }

  private def executionsOf(notation: String): Vector[Option[NodeExecution]] =
    eventsOf(notation).collect { case TestEventLogEvent.ScalaTestEvent(execution, _) => execution }

  private def lastDecodedEventOf(notation: String): DecodedEvent = {
    eventsOf(notation).last match {
      case TestEventLogEvent.ScalaTestEvent(_, decoded) => decoded
      case other => fail(s"the last event is not a ScalaTest event: $other")
    }
  }

  private def lastParentExecutionOf(notation: String): Option[Int] = executionsOf(notation).last.flatMap(_.parentExecution)

  private def throwableOf(written: String): DecodedThrowable = {
    lastDecodedEventOf(s"run: RunAborted(throwable: $written)") match {
      case DecodedEvent.RunAborted(_, Some(throwable), _, _, _, _, _) => throwable
      case other => fail(s"the event has no throwable: $other")
    }
  }

  private def suite(id: Long, parent: Option[Long], position: Option[Int], name: String, suiteId: String, className: String): StructureNode =
    StructureNode.Suite(NodeId(id), parent.map(NodeId(_)), position, name, suiteId, className)

  private def scope(id: Long, parent: Long, position: Int, name: String, label: String): StructureNode =
    StructureNode.Scope(NodeId(id), NodeId(parent), position, name, label)

  private def test(id: Long, parent: Long, position: Int, name: String, label: String): StructureNode =
    StructureNode.Test(NodeId(id), NodeId(parent), position, name, label)

  private def execution(node: Long, number: Int, parentExecution: Option[Int]): Option[NodeExecution] =
    Some(NodeExecution(NodeId(node), number, parentExecution))

  "The test run builder" when {

    "given events and captured lines" should {
      "emit every event and captured line in the order given" in {
        val run = built(
          TestRunPart.Events("run started"),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide"),
          TestRunPart.Events(
            """
            structure
              suite "DivideSuite", class "samples.DivideSuite"
            suite "DivideSuite": SuiteStarting
            """
          ),
          TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
          TestRunPart.Events("run ended completed")
        )

        run.items shouldBe Vector(
          BuiltRunItem.Event(BuiltEvent("run started", TestEventLogEvent.RunStarted(1L, "1970-01-01T00:00:00Z"), FixedParts.none)),
          BuiltRunItem.CapturedLine(CapturedStream.Stderr, "warn: about to divide"),
          BuiltRunItem.Event(
            BuiltEvent(
              "structure\n  suite \"DivideSuite\", class \"samples.DivideSuite\"",
              TestEventLogEvent.Structure(Vector(suite(1, None, None, "DivideSuite", "samples.DivideSuite", "samples.DivideSuite"))),
              FixedParts.none
            )
          ),
          BuiltRunItem.Event(
            BuiltEvent(
              "suite \"DivideSuite\": SuiteStarting",
              TestEventLogEvent.ScalaTestEvent(execution(1, 1, None), DecodedEvent.SuiteStarting(None, "main", 0L)),
              FixedParts.none
            )
          ),
          BuiltRunItem.CapturedLine(CapturedStream.Stdout, "dividing now"),
          BuiltRunItem.Event(BuiltEvent("run ended completed", TestEventLogEvent.RunEnded(RunOutcome.Completed), FixedParts.none))
        )
      }

      "resolve a node that an earlier part's structure holds" in {
        val run = built(TestRunPart.Events(stackSuite), TestRunPart.CapturedLine(CapturedStream.Stdout, "popping"), TestRunPart.Events("""test "pops": TestStarting"""))

        run.events.last.event shouldBe TestEventLogEvent.ScalaTestEvent(execution(3, 1, Some(1)), DecodedEvent.TestStarting(None, "main", 0L))
      }

      "build no event from notation that holds none" in {
        built(TestRunPart.Events("\n   \n")).items shouldBe Vector.empty
      }
    }

    "given how a run ended" should {
      "build the runEnded event of a run that completed" in {
        eventsOf("run ended completed") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Completed))
      }

      "build the runEnded event of a run that was aborted" in {
        eventsOf("run ended aborted") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Aborted))
      }

      "build the runEnded event of a run that was stopped" in {
        eventsOf("run ended stopped") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Stopped))
      }

      "reject any other ending" in {
        rejection("run ended failed") shouldBe Some("a run ends completed, aborted or stopped, not 'failed'")
      }
    }

    "given a suite's first structure" should {
      "give each node its ID, its parent's ID and its declared position among its siblings" in {
        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
                scope "when empty", label "when empty"
                  test "is empty", label "- is empty"
              test "has a top", label "- has a top"
              suite "InnerSuite", class "samples.InnerSuite", id "inner"
                test "inner one", label "- inner one"
          """
        ) shouldBe Vector(
          Vector(
            suite(1, None, None, "OuterSuite", "samples.OuterSuite", "samples.OuterSuite"),
            scope(2, 1, 0, "A Stack", "A Stack"),
            test(3, 2, 0, "pops", "- pops"),
            scope(4, 2, 1, "when empty", "when empty"),
            test(5, 4, 0, "is empty", "- is empty"),
            test(6, 1, 1, "has a top", "- has a top"),
            suite(7, Some(1), Some(2), "InnerSuite", "inner", "samples.InnerSuite"),
            test(8, 7, 0, "inner one", "- inner one")
          )
        )
      }

      "take a suite of another suite ID as another suite, with IDs no earlier structure gave" in {
        structuresOf(
          """
          structure
            suite "SameSuite", class "a.SameSuite"
              test "in a", label "- in a"
          structure
            suite "SameSuite", class "b.SameSuite"
              test "in b", label "- in b"
          """
        ) shouldBe Vector(
          Vector(suite(1, None, None, "SameSuite", "a.SameSuite", "a.SameSuite"), test(2, 1, 0, "in a", "- in a")),
          Vector(suite(3, None, None, "SameSuite", "b.SameSuite", "b.SameSuite"), test(4, 3, 0, "in b", "- in b"))
        )
      }

      "reject a structure that holds no suite" in {
        rejection("structure") shouldBe Some("a structure starts with its suite, indented 2 spaces:\nstructure")
      }

      "reject a structure that starts with a scope" in {
        rejection(
          """
          structure
            scope "A Stack", label "A Stack"
          """
        ) shouldBe Some("a structure starts with its suite, indented 2 spaces:\nstructure\n  scope \"A Stack\", label \"A Stack\"")
      }

      "reject a second tree in one structure" in {
        rejection(
          """
          structure
            suite "OneSuite", class "samples.OneSuite"
            suite "TwoSuite", class "samples.TwoSuite"
          """
        ) shouldBe Some("'suite \"TwoSuite\", class \"samples.TwoSuite\"' is not indented 2 spaces under a suite or scope of its structure")
      }

      "reject a node under a test" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
                test "pushes", label "- pushes"
          """
        ) shouldBe Some("'test \"pushes\", label \"- pushes\"' is not indented 2 spaces under a suite or scope of its structure")
      }

      "reject a suite under a scope" in {
        rejection(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              scope "A Stack", label "A Stack"
                suite "InnerSuite", class "samples.InnerSuite"
          """
        ) shouldBe Some("a suite is nested in a suite, not in a scope: 'suite \"InnerSuite\", class \"samples.InnerSuite\"'")
      }

      "reject a node indented by an odd number of spaces" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
               test "pops", label "- pops"
          """
        ) shouldBe Some("'test \"pops\", label \"- pops\"' is not indented by a multiple of 2 spaces under its structure")
      }

      "reject a line indented under an event" in {
        rejection(
          """
          run started
            suite "StackSuite", class "samples.StackSuite"
          """
        ) shouldBe Some("'suite \"StackSuite\", class \"samples.StackSuite\"' is indented under a line that is not a structure")
      }

      "reject a node that is no suite, scope or test" in {
        rejection(
          """
          structure
            spec "StackSuite", class "samples.StackSuite"
          """
        ) shouldBe Some("expected suite, scope or test at column 1 of 'spec \"StackSuite\", class \"samples.StackSuite\"'")
      }

      "reject two tests of one suite that have one test name" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "A Stack pops", label "- A Stack pops"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          """
        ) shouldBe Some("two tests of one suite have the test name \"A Stack pops\": give 'test \"pops\", label \"- pops\"' its own test name")
      }
    }

    "given a structure under a suite" should {
      "give a suite new to the run its parent's ID and no position" in {
        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "uses a helper", label "- uses a helper"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
              scope "A helper", label "A helper"
                test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(3, Some(1), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          scope(4, 3, 0, "A helper", "A helper"),
          test(5, 4, 0, "helps", "- helps")
        )
      }

      "put the suite under the suite a number picks among those sharing the name" in {
        structuresOf(
          """
          structure
            suite "UserSuites", class "samples.UserSuites"
              suite "UserSuite", class "samples.one.UserSuite"
              suite "UserSuite", class "samples.two.UserSuite"
          structure under suite "UserSuite" #2
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ).last shouldBe Vector(suite(4, Some(3), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"))
      }

      "reject a structure under a scope" in {
        rejection(joined(stackSuite, """
          structure under scope "A Stack"
            suite "HelperSuite", class "samples.HelperSuite"
          """)) shouldBe Some("a structure is under a suite at column 32 of 'structure under scope \"A Stack\"'")
      }

      "reject a structure under a suite no structure holds" in {
        rejection(
          """
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ) shouldBe Some("no structure so far holds suite \"OuterSuite\"")
      }
    }

    "given a further structure for a suite an earlier structure holds" should {
      "keep the ID of a test of the same test name, whichever parent it now has" in {
        structuresOf(
          """
          structure
            suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
                test "pushes", label "- pushes"
          """
        ).last shouldBe Vector(
          suite(1, None, None, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          scope(3, 1, 0, "A Stack", "A Stack"),
          test(2, 3, 0, "pops", "- pops"),
          test(4, 3, 1, "pushes", "- pushes")
        )
      }

      "keep the ID of a test whose written test name is an earlier test's" in {
        structuresOf(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops", test name "stack pops"
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
              test "removes", label "- removes", test name "stack pops"
          """
        ).last shouldBe Vector(
          suite(1, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
          test(3, 1, 0, "pops", "- pops"),
          test(2, 1, 1, "removes", "- removes")
        )
      }

      "keep the ID of a scope of the same name at the same place among its parent's scopes of that name" in {
        structuresOf(
          """
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Stack", label "A Stack"
                test "is empty", label "- is empty"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Queue", label "A Queue"
              scope "A Stack", label "A Stack"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
              scope "A Stack", label "A Stack"
          """
        ).last shouldBe Vector(
          suite(1, None, None, "DupSpec", "samples.DupSpec", "samples.DupSpec"),
          scope(6, 1, 0, "A Queue", "A Queue"),
          scope(2, 1, 1, "A Stack", "A Stack"),
          scope(4, 1, 2, "A Stack", "A Stack"),
          test(5, 4, 0, "pops", "- pops"),
          scope(7, 1, 3, "A Stack", "A Stack")
        )
      }

      "mint an ID for a scope of an earlier scope's name under another parent" in {
        structuresOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                scope "when empty", label "when empty"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Queue", label "A Queue"
                scope "when empty", label "when empty"
          """
        ).last shouldBe Vector(
          suite(1, None, None, "StackSpec", "samples.StackSpec", "samples.StackSpec"),
          scope(4, 1, 0, "A Queue", "A Queue"),
          scope(5, 4, 0, "when empty", "when empty")
        )
      }

      "give a nested suite its parent's ID and the position its first structure gave it" in {
        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "outer one", label "- outer one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          structure under suite "OuterSuite"
            suite "InnerSuite", class "samples.InnerSuite"
              test "inner two", label "- inner two"
              test "inner one", label "- inner one"
          """
        ).last shouldBe Vector(
          suite(3, Some(1), Some(1), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite"),
          test(5, 3, 0, "inner two", "- inner two"),
          test(4, 3, 1, "inner one", "- inner one")
        )
      }

      "give a suite that a structure under a suite added its parent's ID and no position" in {
        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(2, Some(1), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(3, 2, 0, "helps", "- helps")
        )
      }

      "reject a nested suite, which keeps the structure and place it has" in {
        rejection(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "InnerSuite", class "samples.InnerSuite"
          """
        ) shouldBe Some("a further structure holds its suite's scopes and tests only, not 'suite \"InnerSuite\", class \"samples.InnerSuite\"'")
      }

      "reject another class for the suite" in {
        rejection(
          """
          structure
            suite "StackSpec", class "samples.StackSpec", id "stack"
          structure
            suite "StackSpec", class "samples.OtherSpec", id "stack"
          """
        ) shouldBe Some(
          "a further structure keeps its suite's class, samples.StackSpec:\nstructure\n  suite \"StackSpec\", class \"samples.OtherSpec\", id \"stack\""
        )
      }
    }

    "given an event that names a node" should {
      "assign the event to the node of that kind and name" in {
        executionsOf(
          """
          structure
            suite "pops", class "samples.pops"
              scope "pops", label "pops"
                test "pops", label "- pops"
          test "pops": TestStarting
          scope "pops": ScopeOpened
          suite "pops": SuiteStarting
          """
        ) shouldBe Vector(execution(3, 1, Some(1)), execution(2, 1, Some(1)), execution(1, 1, None))
      }

      "assign the event to the node its number picks among those sharing a name, in the order structures first hold them" in {
        executionsOf(
          """
          structure
            suite "TwiceSuites", class "samples.TwiceSuites"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          test "inner one" #1: TestStarting
          structure
            suite "InnerSuite", class "samples.other.InnerSuite"
              test "inner one", label "- inner one"
          test "inner one" #3: TestStarting
          suite "InnerSuite" #2: SuiteStarting
          test "inner one" #2: TestStarting
          suite "InnerSuite" #3: SuiteStarting
          """
        ) shouldBe Vector(
          execution(3, 1, Some(1)),
          execution(7, 1, Some(1)),
          execution(4, 1, None),
          execution(5, 1, Some(1)),
          execution(6, 1, None)
        )
      }

      "name a node as the latest structure names it" in {
        executionsOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          test "A Stack pops": TestStarting
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          test "pops": TestSucceeded
          """
        ) shouldBe Vector(execution(2, 1, Some(1)), execution(2, 1, Some(1)))
      }

      "reject the name an earlier structure gave a node that a later one renamed" in {
        rejection(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          test "A Stack pops": TestSucceeded
          """
        ) shouldBe Some("no structure so far holds test \"A Stack pops\"")
      }

      "assign an event of the run to no node" in {
        eventsOf("""run: AlertProvided(message: "slow")""") shouldBe Vector(
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.AlertProvided("slow", None, None, "main", 0L))
        )
      }

      "reject a node that only a later structure holds" in {
        rejection(joined("""test "pops": TestStarting""", stackSuite)) shouldBe Some("no structure so far holds test \"pops\"")
      }

      "reject a node of another kind than the one that has the name" in {
        rejection(joined(stackSuite, """scope "pops": ScopeOpened""")) shouldBe Some("no structure so far holds scope \"pops\"")
      }

      "reject a name that several nodes share where the event gives no number" in {
        rejection(
          """
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Stack", label "A Stack"
              scope "A Stack", label "A Stack"
          scope "A Stack": ScopeOpened
          """
        ) shouldBe Some("the structures so far hold 2 nodes named scope \"A Stack\": add #<n>")
      }

      "reject a number beyond the nodes that share the name" in {
        rejection(joined(stackSuite, """test "pops" #2: TestStarting""")) shouldBe Some("no structure so far holds test \"pops\" #2: 1 share that name")
      }

      "reject an execution for an event of the run" in {
        rejection("run execution 2: RunStopped") shouldBe Some("an event assigned to the run has no execution: 'run execution 2: RunStopped'")
      }

      "reject a line that names no run, suite, scope or test" in {
        rejection("""spec "pops": TestStarting""") shouldBe Some("expected run, suite, scope or test at column 1 of 'spec \"pops\": TestStarting'")
      }

      "reject text after the event" in {
        rejection("run: RunStopped and more") shouldBe Some("unexpected text at column 16 of 'run: RunStopped and more'")
      }
    }

    "numbering an event's execution" should {
      "give the node's first execution where the event writes none" in {
        executionsOf(joined(stackSuite, """test "pops": TestStarting""")) shouldBe Vector(execution(3, 1, Some(1)))
      }

      "give the execution the event writes" in {
        executionsOf(joined(stackSuite, """test "pops" execution 4: TestStarting""")) shouldBe Vector(execution(3, 4, Some(1)))
      }

      "give a suite's event no parent execution, nested or not" in {
        executionsOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "InnerSuite", class "samples.InnerSuite"
          suite "OuterSuite" execution 2: SuiteStarting
          suite "InnerSuite": SuiteStarting
          """
        ) shouldBe Vector(execution(1, 2, None), execution(2, 1, None))
      }

      "give a scope's or test's event the parent execution it writes" in {
        executionsOf(
          joined(stackSuite, """
          scope "A Stack", parent execution 5: ScopeOpened
          test "pops" execution 2, parent execution 3: TestStarting
          """)
        ) shouldBe Vector(execution(2, 1, Some(5)), execution(3, 2, Some(3)))
      }

      "reject a parent execution for a suite's event" in {
        rejection(joined(stackSuite, """suite "StackSuite", parent execution 1: SuiteStarting""")) shouldBe
          Some("a suite's event has no parent execution: 'suite \"StackSuite\", parent execution 1: SuiteStarting'")
      }

      "reject an execution below 1" in {
        rejection(joined(stackSuite, """test "pops" execution 0: TestStarting""")) shouldBe
          Some("expected a number from 1 at column 23 of 'test \"pops\" execution 0: TestStarting'")
      }
    }

    "defaulting a scope's or test's parent execution" should {
      "take the parent's latest execution that is open" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 3: ScopeOpened
          scope "A Stack" execution 3: ScopeClosed
          test "pops": TestStarting
          """)
        ) shouldBe Some(2)
      }

      "take the parent's latest execution where none is open" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 2: ScopeClosed
          scope "A Stack": ScopeClosed
          test "pops": TestStarting
          """)
        ) shouldBe Some(2)
      }

      "take the parent's first execution where the parent has none" in {
        lastParentExecutionOf(joined(stackSuite, """test "pops" execution 2: TestStarting""")) shouldBe Some(1)
      }

      "count an execution open from any first event of it" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          scope "A Stack" execution 2: ScopeClosed
          scope "A Stack": InfoProvided(message: "checked")
          test "pops": TestStarting
          """)
        ) shouldBe Some(1)
      }

      "count a scope's execution closed by ScopePending" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 2: ScopePending
          test "pops": TestStarting
          """)
        ) shouldBe Some(1)
      }

      "count a suite's execution closed by SuiteCompleted" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          suite "StackSuite": SuiteStarting
          suite "StackSuite" execution 2: SuiteStarting
          suite "StackSuite" execution 2: SuiteCompleted
          scope "A Stack": ScopeOpened
          """)
        ) shouldBe Some(1)
      }

      "count a suite's execution closed by SuiteAborted" in {
        lastParentExecutionOf(
          joined(stackSuite, """
          suite "StackSuite": SuiteStarting
          suite "StackSuite" execution 2: SuiteStarting
          suite "StackSuite" execution 2: SuiteAborted(throwable: java.lang.RuntimeException "boom")
          scope "A Stack": ScopeOpened
          """)
        ) shouldBe Some(1)
      }

      "take the execution of the parent the latest structure gives the node" in {
        lastParentExecutionOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          suite "StackSpec" execution 3: SuiteStarting
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          scope "A Stack" execution 2: ScopeOpened
          test "pops": TestStarting
          """
        ) shouldBe Some(2)
      }
    }

    "given a ScalaTest event that writes no field" should {
      "build each event class with no optional field, an empty message and empty lists" in {
        val expectedByClassName = Vector(
          "TestStarting" -> DecodedEvent.TestStarting(None, "main", 0L),
          "TestSucceeded" -> DecodedEvent.TestSucceeded(None, None, "main", 0L),
          "TestFailed" -> DecodedEvent.TestFailed("", Vector.empty, None, None, None, "main", 0L),
          "TestIgnored" -> DecodedEvent.TestIgnored(None, "main", 0L),
          "TestPending" -> DecodedEvent.TestPending(None, None, "main", 0L),
          "TestCanceled" -> DecodedEvent.TestCanceled("", None, None, None, "main", 0L),
          "SuiteStarting" -> DecodedEvent.SuiteStarting(None, "main", 0L),
          "SuiteCompleted" -> DecodedEvent.SuiteCompleted(None, None, "main", 0L),
          "SuiteAborted" -> DecodedEvent.SuiteAborted("", None, None, None, "main", 0L),
          "RunStarting" -> DecodedEvent.RunStarting(0, Map.empty, None, "main", 0L),
          "RunCompleted" -> DecodedEvent.RunCompleted(None, None, None, "main", 0L),
          "RunStopped" -> DecodedEvent.RunStopped(None, None, None, "main", 0L),
          "RunAborted" -> DecodedEvent.RunAborted("", None, None, None, None, "main", 0L),
          "InfoProvided" -> DecodedEvent.InfoProvided("", None, None, "main", 0L),
          "AlertProvided" -> DecodedEvent.AlertProvided("", None, None, "main", 0L),
          "NoteProvided" -> DecodedEvent.NoteProvided("", None, None, "main", 0L),
          "MarkupProvided" -> DecodedEvent.MarkupProvided("", None, "main", 0L),
          "ScopeOpened" -> DecodedEvent.ScopeOpened(None, "main", 0L),
          "ScopeClosed" -> DecodedEvent.ScopeClosed(None, "main", 0L),
          "ScopePending" -> DecodedEvent.ScopePending(None, "main", 0L),
          "DiscoveryStarting" -> DecodedEvent.DiscoveryStarting("main", 0L),
          "DiscoveryCompleted" -> DecodedEvent.DiscoveryCompleted(None, "main", 0L)
        )

        expectedByClassName.map { case (className, _) => className -> lastDecodedEventOf(s"run: $className") } shouldBe expectedByClassName
      }

      "reject a class the test event log does not carry" in {
        rejection("run: TestExploded") shouldBe Some("the test event log has no event class TestExploded: 'run: TestExploded'")
      }
    }

    "given a ScalaTest event that writes fields" should {
      "build a failed test's fields" in {
        lastDecodedEventOf(
          """run: TestFailed(message: "1 was not 0", analysis: ["Stack(size: 1 -> 0)", "second"], duration: 12, location: LineInFile(7, "FailSuite.scala", None), threadName: "worker-3", timeStamp: 1700000000000)"""
        ) shouldBe DecodedEvent.TestFailed(
          "1 was not 0",
          Vector("Stack(size: 1 -> 0)", "second"),
          None,
          Some(12L),
          Some(LineInFile(7, "FailSuite.scala", None)),
          "worker-3",
          1700000000000L
        )
      }

      "build a markup's text" in {
        lastDecodedEventOf("""run: MarkupProvided(text: "*markup*")""") shouldBe DecodedEvent.MarkupProvided("*markup*", None, "main", 0L)
      }

      "build a starting run's test count" in {
        lastDecodedEventOf("run: RunStarting(testCount: 42)") shouldBe DecodedEvent.RunStarting(42, Map.empty, None, "main", 0L)
      }

      "build an empty list" in {
        lastDecodedEventOf("run: TestFailed(analysis: [])") shouldBe DecodedEvent.TestFailed("", Vector.empty, None, None, None, "main", 0L)
      }

      "build a location that is a line in a file with a pathname" in {
        lastDecodedEventOf("""run: TestStarting(location: LineInFile(7, "FailSuite.scala", Some("/src/FailSuite.scala")))""") shouldBe
          DecodedEvent.TestStarting(Some(LineInFile(7, "FailSuite.scala", Some("/src/FailSuite.scala"))), "main", 0L)
      }

      "build a location that is the top of a class" in {
        lastDecodedEventOf("""run: SuiteStarting(location: TopOfClass("samples.FailSuite"))""") shouldBe
          DecodedEvent.SuiteStarting(Some(TopOfClass("samples.FailSuite")), "main", 0L)
      }

      "build a location that is the top of a method" in {
        lastDecodedEventOf("""run: TestStarting(location: TopOfMethod("samples.FailSuite", "public void samples.FailSuite.fails()"))""") shouldBe
          DecodedEvent.TestStarting(Some(TopOfMethod("samples.FailSuite", "public void samples.FailSuite.fails()")), "main", 0L)
      }

      "build a location that defers to the stack depth exception" in {
        lastDecodedEventOf("run: TestFailed(location: SeeStackDepthException)") shouldBe
          DecodedEvent.TestFailed("", Vector.empty, None, None, Some(SeeStackDepthException), "main", 0L)
      }

      "read a string's escapes, a lone surrogate among them" in {
        lastDecodedEventOf("""run: InfoProvided(message: "line\n\r\ttab \"quoted\" back\\slash \u00e9 \uD800 end")""") shouldBe
          DecodedEvent.InfoProvided("line\n\r\ttab \"quoted\" back\\slash " + 0xE9.toChar + " " + 0xD800.toChar + " end", None, None, "main", 0L)
      }

      "reject a field the event's class does not have" in {
        rejection("""run: RunStopped(message: "stopped")""") shouldBe Some("a RunStopped has no message: 'run: RunStopped(message: \"stopped\")'")
      }

      "reject a field the notation cannot write" in {
        rejection("run: RunCompleted(summary: 3)") shouldBe
          Some("the notation writes no field 'summary' at column 28 of 'run: RunCompleted(summary: 3)'")
      }

      "reject a field written twice" in {
        rejection("""run: InfoProvided(message: "one", message: "two")""") shouldBe
          Some("an event writes its message once: 'run: InfoProvided(message: \"one\", message: \"two\")'")
      }

      "reject an escape Java's string literals do not have" in {
        rejection("""run: InfoProvided(message: "a\qb")""") shouldBe
          Some("the notation has no escape '\\q' at column 31 of 'run: InfoProvided(message: \"a\\qb\")'")
      }

      "reject a Unicode escape of fewer than 4 hexadecimal digits" in {
        rejection("""run: InfoProvided(message: "\u12G4")""") shouldBe
          Some("expected 4 hexadecimal digits at column 31 of 'run: InfoProvided(message: \"\\u12G4\")'")
      }

      "reject a string that is not closed" in {
        rejection("""run: InfoProvided(message: "open)""") shouldBe
          Some("a string that is not closed at column 34 of 'run: InfoProvided(message: \"open)'")
      }

      "reject a number where the field holds a string" in {
        rejection("run: InfoProvided(message: 3)") shouldBe Some("expected '\"' at column 28 of 'run: InfoProvided(message: 3)'")
      }

      "reject a number that does not fit its field" in {
        rejection("run: TestSucceeded(duration: 9223372036854775808)") shouldBe
          Some("'9223372036854775808' does not fit a Long at column 30 of 'run: TestSucceeded(duration: 9223372036854775808)'")
      }

      "reject a test count that does not fit an Int" in {
        rejection("run: RunStarting(testCount: 2147483648)") shouldBe
          Some("the test count 2147483648 does not fit an Int: 'run: RunStarting(testCount: 2147483648)'")
      }

      "reject a location that is not one of the Location classes" in {
        rejection("run: TestStarting(location: Somewhere)") shouldBe Some(
          "expected SeeStackDepthException, TopOfClass, TopOfMethod or LineInFile at column 29 of 'run: TestStarting(location: Somewhere)'"
        )
      }
    }

    "defaulting an event's message" should {
      "take the message the event writes, whatever its throwable's" in {
        lastDecodedEventOf("""run: RunAborted(message: "ran out", throwable: java.lang.OutOfMemoryError "heap")""") match {
          case DecodedEvent.RunAborted(message, throwable, None, None, None, "main", 0L) =>
            message shouldBe "ran out"
            throwable.flatMap(_.message) shouldBe Some("heap")
          case other => fail(s"not the RunAborted given: $other")
        }
      }

      "take its throwable's message" in {
        lastDecodedEventOf("""run: TestCanceled(throwable: java.lang.IllegalStateException "boom")""") match {
          case DecodedEvent.TestCanceled(message, Some(_), None, None, "main", 0L) => message shouldBe "boom"
          case other => fail(s"not the TestCanceled given: $other")
        }
      }

      "be empty where its throwable's message is null" in {
        lastDecodedEventOf("run: SuiteAborted(throwable: java.lang.IllegalStateException null)") match {
          case DecodedEvent.SuiteAborted(message, Some(_), None, None, "main", 0L) => message shouldBe ""
          case other => fail(s"not the SuiteAborted given: $other")
        }
      }

      "be empty where its throwable writes no message" in {
        lastDecodedEventOf("run: NoteProvided(throwable: java.lang.IllegalStateException)") match {
          case DecodedEvent.NoteProvided(message, Some(_), None, "main", 0L) => message shouldBe ""
          case other => fail(s"not the NoteProvided given: $other")
        }
      }
    }

    "given a throwable" should {
      "build one that writes only its class with a null message and no other part" in {
        val throwable = throwableOf("java.lang.IllegalStateException")

        throwable.className shouldBe "java.lang.IllegalStateException"
        throwable.message shouldBe None
        throwable.stackDepth shouldBe None
        throwable.isPropertyCheckFailure shouldBe false
        throwable.stackTrace shouldBe Vector.empty
        throwable.suppressed shouldBe Vector.empty
        throwable.cause shouldBe None
      }

      "build its message" in {
        throwableOf("""samples.Outer$Inner_Failure "went \"wrong\"" """.trim).message shouldBe Some("went \"wrong\"")
      }

      "build a class name that holds a dollar sign and an underscore" in {
        throwableOf("""samples.Outer$Inner_Failure "went wrong"""").className shouldBe "samples.Outer$Inner_Failure"
      }

      "build a null message it writes" in {
        throwableOf("java.lang.IllegalStateException null at StackSpec.scala:9").message shouldBe None
      }

      "build a StackDepth's file name and line number, with no pathname" in {
        throwableOf("""org.scalatest.exceptions.TestFailedException "1 was not 0" at StackFlatSpec.scala:9""").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), None, Some(9)))
      }

      "build a StackDepth's pathname, whose last segment is the file name" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at /src/samples/StackFlatSpec.scala:9").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), Some("/src/samples/StackFlatSpec.scala"), Some(9)))
      }

      "build a StackDepth's Windows pathname" in {
        throwableOf("""org.scalatest.exceptions.TestFailedException at C:\src\StackFlatSpec.scala:9""").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), Some("""C:\src\StackFlatSpec.scala"""), Some(9)))
      }

      "build a StackDepth whose file name is undefined" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at ?:9").stackDepth shouldBe Some(DecodedStackDepth(None, None, Some(9)))
      }

      "build a StackDepth whose line number is undefined" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at StackFlatSpec.scala:?").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), None, None))
      }

      "build a property check failure" in {
        val throwable = throwableOf(
          """org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException "falsified" at StackProps.scala:12 property check"""
        )

        throwable.isPropertyCheckFailure shouldBe true
        throwable.stackDepth shouldBe Some(DecodedStackDepth(Some("StackProps.scala"), None, Some(12)))
      }

      "build each frame's fields from the text StackTraceElement.toString gives it" in {
        throwableOf(
          """java.lang.IllegalStateException frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)", "samples.Native.call(Native Method)", "samples.Unknown.run(Unknown Source)", "samples.NoLine.<init>(NoLine.scala)", "app//samples.mod@1.0/samples.InModule.g(InModule.scala:4)"]"""
        ).stackTrace shouldBe Vector(
          DecodedStackFrame("samples.ThrowSuite", "f", Some("ThrowSuite.scala"), Some(9), false, "samples.ThrowSuite.f(ThrowSuite.scala:9)"),
          DecodedStackFrame("samples.Native", "call", None, None, true, "samples.Native.call(Native Method)"),
          DecodedStackFrame("samples.Unknown", "run", None, None, false, "samples.Unknown.run(Unknown Source)"),
          DecodedStackFrame("samples.NoLine", "<init>", Some("NoLine.scala"), None, false, "samples.NoLine.<init>(NoLine.scala)"),
          DecodedStackFrame(
            "samples.InModule",
            "g",
            Some("InModule.scala"),
            Some(4),
            false,
            "app//samples.mod@1.0/samples.InModule.g(InModule.scala:4)"
          )
        )
      }

      "build a chain of causes" in {
        val outer = throwableOf("""java.lang.IllegalStateException "outer" caused by java.io.IOException "middle" caused by java.lang.Error "inner"""")

        val middle = outer.cause.getOrElse(fail("the outer throwable has no cause"))
        val inner = middle.cause.getOrElse(fail("the middle throwable has no cause"))
        (outer.className, outer.message) shouldBe (("java.lang.IllegalStateException", Some("outer")))
        (middle.className, middle.message) shouldBe (("java.io.IOException", Some("middle")))
        (inner.className, inner.message, inner.cause) shouldBe (("java.lang.Error", Some("inner"), None))
      }

      "build suppressed throwables in order, each with its own parts" in {
        val outer = throwableOf(
          """java.lang.IllegalStateException "outer" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"] suppressing [java.lang.RuntimeException "closing" caused by java.lang.Error "deep", java.lang.Exception suppressing [java.lang.Error "nested"]] caused by java.io.IOException "inner""""
        )

        outer.stackTrace.map(_.text) shouldBe Vector("samples.ThrowSuite.f(ThrowSuite.scala:9)")
        outer.suppressed.map(suppressed => (suppressed.className, suppressed.message)) shouldBe Vector(
          ("java.lang.RuntimeException", Some("closing")),
          ("java.lang.Exception", None)
        )
        outer.suppressed(0).cause.map(cause => (cause.className, cause.message)) shouldBe Some(("java.lang.Error", Some("deep")))
        outer.suppressed(0).suppressed shouldBe Vector.empty
        outer.suppressed(1).cause shouldBe None
        outer.suppressed(1).suppressed.map(nested => (nested.className, nested.message)) shouldBe Vector(("java.lang.Error", Some("nested")))
        outer.cause.map(cause => (cause.className, cause.message, cause.suppressed.size)) shouldBe Some(("java.io.IOException", Some("inner"), 0))
      }

      "reject a frame that is not the text StackTraceElement.toString gives" in {
        rejection("""run: RunAborted(throwable: java.lang.Error frames ["not a frame"])""") shouldBe
          Some("'not a frame' is not the text StackTraceElement.toString gives a frame")
      }

      "reject a frame whose line number is not a number" in {
        rejection("""run: RunAborted(throwable: java.lang.Error frames ["samples.A.f(A.scala:nine)"])""") shouldBe
          Some("'samples.A.f(A.scala:nine)' holds a line number that is not a number")
      }

      "reject failed code with no line number" in {
        rejection("run: RunAborted(throwable: java.lang.Error at StackSpec.scala)") shouldBe
          Some("'StackSpec.scala' is not <file name>:<line number> at column 62 of 'run: RunAborted(throwable: java.lang.Error at StackSpec.scala)'")
      }

      "reject failed code whose line number is not a number" in {
        rejection("run: RunAborted(throwable: java.lang.Error at StackSpec.scala:nine)") shouldBe
          Some("'nine' is not a line number at column 67 of 'run: RunAborted(throwable: java.lang.Error at StackSpec.scala:nine)'")
      }

      "reject parts written out of order" in {
        rejection("""run: RunAborted(throwable: java.lang.Error property check at StackSpec.scala:9)""") shouldBe
          Some("expected ')' at column 58 of 'run: RunAborted(throwable: java.lang.Error property check at StackSpec.scala:9)'")
      }
    }

    "recording what an event fixes" should {
      "record the fields and throwable parts a ScalaTest event writes" in {
        val event = built(
          TestRunPart.Events(
            joined(stackSuite, """test "pops": TestFailed(throwable: java.lang.IllegalStateException null at StackSpec.scala:? property check frames [] suppressing [java.lang.Error] caused by java.io.IOException "inner", analysis: [])""")
          )
        ).events.last

        event.fixed shouldBe FixedParts(
          Set("throwable", "analysis"),
          false,
          Some(
            WrittenThrowable(
              "java.lang.IllegalStateException",
              WrittenMessage.Null,
              Some(WrittenStackDepth(Some("StackSpec.scala"), None)),
              true,
              Some(Vector.empty),
              Some(Vector(WrittenThrowable("java.lang.Error", WrittenMessage.Unwritten, None, false, None, None, None))),
              Some(WrittenThrowable("java.io.IOException", WrittenMessage.Text("inner"), None, false, None, None, None))
            )
          )
        )
      }

      "record a parent execution the event writes" in {
        built(TestRunPart.Events(joined(stackSuite, """test "pops", parent execution 1: TestStarting"""))).events.last.fixed shouldBe
          FixedParts(Set.empty, true, None)
      }

      "record no parent execution where the event leaves it to the default" in {
        built(TestRunPart.Events(joined(stackSuite, """test "pops" execution 2: TestStarting"""))).events.last.fixed shouldBe FixedParts.none
      }

      "keep each event's own notation" in {
        built(TestRunPart.Events(joined(stackSuite, """test "pops": TestStarting"""))).events.map(_.notation) shouldBe Vector(
          "structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    scope \"A Stack\", label \"A Stack\"\n      test \"pops\", label \"- pops\"",
          "test \"pops\": TestStarting"
        )
      }
    }
  }
}

package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import EventNotationBlocks.joined

final class EventLogComparisonSpec extends AnyWordSpec with Matchers {

  private val stackSuite =
    """
    structure
      suite "StackSuite", class "samples.StackSuite"
        scope "A Stack", label "A Stack"
          test "pops", label "- pops"
          test "pushes", label "- pushes"
    """

  private val stackSuiteText =
    "structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    scope \"A Stack\", label \"A Stack\"\n" +
      "      test \"pops\", label \"- pops\"\n      test \"pushes\", label \"- pushes\""

  private val stackSuiteNode = NodeId(Vector(NodePlace.Suite("samples.StackSuite", 0)))

  private def under(parent: NodeId, place: NodePlace): NodeId = NodeId(parent.places :+ place)

  private def expected(blocks: String*): Vector[BuiltEvent] = {
    TestRunBuilder.build(TestRunPart.Events(joined(blocks*))) match {
      case Right(run) => run.events
      case Left(reason) => fail(s"the run was not built: $reason")
    }
  }

  private def log(blocks: String*): Vector[TestEventLogEvent] = expected(blocks*).map(_.event)

  // Each of the log's node IDs starts with a place the expected IDs lack, so no node has its expected node's ID.
  private def logWithOtherNodeIds(blocks: String*): Vector[TestEventLogEvent] = {
    def otherId(id: NodeId): NodeId = NodeId(NodePlace.Suite("samples.OtherSuite", 0) +: id.places)
    log(blocks*).map {
      case TestEventLogEvent.Structure(tree) =>
        TestEventLogEvent.Structure(tree.map {
          case suite: StructureNode.Suite => suite.copy(id = otherId(suite.id), parent = suite.parent.map(otherId))
          case scope: StructureNode.Scope => scope.copy(id = otherId(scope.id), parent = otherId(scope.parent))
          case test: StructureNode.Test => test.copy(id = otherId(test.id), parent = otherId(test.parent))
        })
      case TestEventLogEvent.ScalaTestEvent(execution, event) =>
        TestEventLogEvent.ScalaTestEvent(execution.map(assigned => assigned.copy(node = otherId(assigned.node))), event)
      case unassigned => unassigned
    }
  }

  private def mismatchOfLastEvent(expectedEvent: String, loggedEvent: String): Option[String] =
    EventLogComparison.mismatchWhenIs(log(stackSuite, loggedEvent), expected(stackSuite, expectedEvent))

  private def mismatchOfThrowable(expectedThrowable: String, loggedThrowable: String): Option[String] =
    mismatchOfLastEvent(s"run: RunAborted(throwable: $expectedThrowable)", s"run: RunAborted(throwable: $loggedThrowable)")

  private def notLastEvent(expectedEvent: String, reason: String): Option[String] =
    Some(s"event 2 of the log is not '$expectedEvent': $reason")

  private def notThrowable(expectedThrowable: String, reason: String): Option[String] =
    notLastEvent(s"run: RunAborted(throwable: $expectedThrowable)", reason)

  private val wholeRun =
    """
    run started
    run: RunStarting
    structure
      suite "StackSuite", class "samples.StackSuite"
        scope "A Stack", label "A Stack"
          test "pops", label "- pops"
    suite "StackSuite": SuiteStarting
    scope "A Stack": ScopeOpened
    test "pops": TestStarting
    test "pops": TestFailed(throwable: java.lang.IllegalStateException "boom")
    scope "A Stack": ScopeClosed
    suite "StackSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """

  "Comparing a log with the events it is expected to be" should {
    "find no mismatch where the log is those events" in {
      EventLogComparison.mismatchWhenIs(log(wholeRun), expected(wholeRun)) shouldBe None
    }

    "find no mismatch where the log gives its nodes other IDs" in {
      EventLogComparison.mismatchWhenIs(logWithOtherNodeIds(wholeRun), expected(wholeRun)) shouldBe None
    }

    "find no mismatch between an empty log and no events" in {
      EventLogComparison.mismatchWhenIs(Vector.empty, Vector.empty) shouldBe None
    }

    "report a log that holds one event too many" in {
      EventLogComparison.mismatchWhenIs(log("run started", "run ended completed"), expected("run started")) shouldBe
        Some("the log holds 2 events, not the 1 expected")
    }

    "report a log that holds one event too few" in {
      EventLogComparison.mismatchWhenIs(log("run started"), expected("run started", "run ended completed")) shouldBe
        Some("the log holds 1 events, not the 2 expected")
    }

    "report the first event that does not match, before a difference in length" in {
      EventLogComparison.mismatchWhenIs(
        log("run started", "run ended aborted", "run ended stopped"),
        expected("run started", "run ended completed")
      ) shouldBe Some("event 2 of the log is not 'run ended completed': its outcome is Aborted, not Completed")
    }

    "report a structure event where a runStarted event is expected" in {
      EventLogComparison.mismatchWhenIs(log(stackSuite), expected("run started")) shouldBe
        Some("event 1 of the log is not 'run started': it is a structure event")
    }

    "report a runStarted event where a runEnded event is expected" in {
      EventLogComparison.mismatchWhenIs(log("run started"), expected("run ended stopped")) shouldBe
        Some("event 1 of the log is not 'run ended stopped': it is a runStarted event")
    }

    "report a runEnded event where a ScalaTest event is expected" in {
      EventLogComparison.mismatchWhenIs(log("run ended stopped"), expected("run: RunStopped")) shouldBe
        Some("event 1 of the log is not 'run: RunStopped': it is a runEnded event")
    }

    "report a ScalaTest event where a structure event is expected" in {
      EventLogComparison.mismatchWhenIs(log("run: RunStopped"), expected(stackSuite)) shouldBe
        Some(s"event 1 of the log is not '$stackSuiteText': it is a RunStopped")
    }

    "match a runStarted event whatever its PID and start time" in {
      EventLogComparison.mismatchWhenIs(Vector(TestEventLogEvent.RunStarted(4242L, "2026-11-13T02:54:37.123456Z")), expected("run started")) shouldBe None
    }
  }

  "Comparing a structure event" should {
    "report a tree with another number of nodes" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "A Stack", label "A Stack"
          """
        ),
        expected(stackSuite)
      ) shouldBe Some(s"event 1 of the log is not '$stackSuiteText': it holds 2 nodes, not 4")
    }

    "report a node with another label" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "* pops"
                test "pushes", label "- pushes"
          """
        ),
        expected(stackSuite)
      ) shouldBe Some(
        s"event 1 of the log is not '$stackSuiteText': " +
          "its node 3 is test \"pops\", label \"* pops\", position 0, not test \"pops\", label \"- pops\", position 0"
      )
    }

    "report nodes in another declared order" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "A Stack", label "A Stack"
                test "pushes", label "- pushes"
                test "pops", label "- pops"
          """
        ),
        expected(stackSuite)
      ) shouldBe Some(
        s"event 1 of the log is not '$stackSuiteText': " +
          "its node 3 is test \"pushes\", label \"- pushes\", position 0, not test \"pops\", label \"- pops\", position 0"
      )
    }

    "report a node of another kind" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "A Stack", label "A Stack"
          """
        ),
        expected(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "A Stack", label "A Stack"
          """
        )
      ) shouldBe Some(
        "event 1 of the log is not 'structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    scope \"A Stack\", label \"A Stack\"': " +
          "its node 2 is test \"A Stack\", label \"A Stack\", position 0, not scope \"A Stack\", label \"A Stack\", position 0"
      )
    }

    "report a suite with another suite ID" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite", id "stack"
          """
        ),
        expected(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
          """
        )
      ) shouldBe Some(
        "event 1 of the log is not 'structure\n  suite \"StackSuite\", class \"samples.StackSuite\"': " +
          "its node 1 is suite \"StackSuite\", class \"samples.StackSuite\", id \"stack\", no position, " +
          "not suite \"StackSuite\", class \"samples.StackSuite\", id \"samples.StackSuite\", no position"
      )
    }

    "report a scope whose name and label differ, though they read the same written one after the other" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "a\", label \"b", label "c"
          """
        ),
        expected(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              scope "a", label "b\", label \"c"
          """
        )
      ) shouldBe Some(
        "event 1 of the log is not 'structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    scope \"a\", label \"b\\\", label \\\"c\"': " +
          "its node 2 is scope \"a\\\", label \\\"b\", label \"c\", position 0, not scope \"a\", label \"b\\\", label \\\"c\", position 0"
      )
    }

    "report a suite whose name and class differ, though they read the same written one after the other" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "a\", class \"b", class "c", id "x\\y"
          """
        ),
        expected(
          """
          structure
            suite "a", class "b\", class \"c", id "x\\y"
          """
        )
      ) shouldBe Some(
        "event 1 of the log is not 'structure\n  suite \"a\", class \"b\\\", class \\\"c\", id \"x\\\\y\"': " +
          "its node 1 is suite \"a\\\", class \\\"b\", class \"c\", id \"x\\\\y\", no position, " +
          "not suite \"a\", class \"b\\\", class \\\"c\", id \"x\\\\y\", no position"
      )
    }

    "report a test the log marks ignored where the expected structure does not" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops", ignored
          """
        ),
        expected(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
          """
        )
      ) shouldBe Some(
        "event 1 of the log is not 'structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    test \"pops\", label \"- pops\"': " +
          "its node 2 is test \"pops\", label \"- pops\", position 0, ignored, not test \"pops\", label \"- pops\", position 0"
      )
    }

    "report a nested suite at another position" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "InnerSuite", class "samples.InnerSuite"
          structure under suite "OuterSuite"
            suite "InnerSuite", class "samples.InnerSuite"
          """
        ).drop(1),
        expected(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "InnerSuite", class "samples.InnerSuite"
          """
        ).drop(1)
      ) shouldBe Some(
        "event 1 of the log is not 'structure under suite \"OuterSuite\"\n  suite \"InnerSuite\", class \"samples.InnerSuite\"': " +
          "its node 1 is suite \"InnerSuite\", class \"samples.InnerSuite\", id \"samples.InnerSuite\", position 0, " +
          "not suite \"InnerSuite\", class \"samples.InnerSuite\", id \"samples.InnerSuite\", no position"
      )
    }

    "report a node under another parent" in {
      val scope = under(stackSuiteNode, NodePlace.Scope("A Stack", 0))
      val test = under(stackSuiteNode, NodePlace.Test("A Stack pops"))
      val expectedTree = BuiltEvent(
        "structure",
        TestEventLogEvent.Structure(
          Vector(
            StructureNode.Suite(stackSuiteNode, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
            StructureNode.Scope(scope, stackSuiteNode, 0, "A Stack", "A Stack"),
            StructureNode.Test(test, scope, 0, "pops", "- pops")
          )
        ),
        WrittenParts.none
      )
      val loggedTree = TestEventLogEvent.Structure(
        Vector(
          StructureNode.Suite(stackSuiteNode, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
          StructureNode.Scope(scope, stackSuiteNode, 0, "A Stack", "A Stack"),
          StructureNode.Test(test, stackSuiteNode, 0, "pops", "- pops")
        )
      )

      EventLogComparison.mismatchWhenIs(Vector(loggedTree), Vector(expectedTree)) shouldBe
        Some("event 1 of the log is not 'structure': its node 3 is under another parent")
    }

    "report a nested suite where a top-level suite is expected" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ),
        expected(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure
            suite "HelperSuite", class "samples.HelperSuite"
          """
        )
      ) shouldBe Some(
        "event 2 of the log is not 'structure\n  suite \"HelperSuite\", class \"samples.HelperSuite\"': its node 1 has a parent, where none is expected"
      )
    }

    "report a top-level suite where a nested suite is expected" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ),
        expected(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          """
        )
      ) shouldBe Some(
        "event 2 of the log is not 'structure under suite \"OuterSuite\"\n  suite \"HelperSuite\", class \"samples.HelperSuite\"': " +
          "its node 1 has no parent, where one is expected"
      )
    }

    "match a further structure whose nodes keep the IDs the log gave them" in {
      val further =
        """
        structure
          suite "StackSpec", class "samples.StackSpec"
            test "A Stack pops", label "- A Stack pops"
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        """

      EventLogComparison.mismatchWhenIs(logWithOtherNodeIds(further), expected(further)) shouldBe None
    }

    "report a further structure that gives a kept node another ID" in {
      EventLogComparison.mismatchWhenIs(
        log(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops", test name "another test"
          """
        ),
        expected(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          """
        )
      ) shouldBe Some(
        "event 2 of the log is not 'structure\n  suite \"StackSpec\", class \"samples.StackSpec\"\n    scope \"A Stack\", label \"A Stack\"\n" +
          "      test \"pops\", label \"- pops\"': its node 3 is not the node an earlier structure held there"
      )
    }

    "report a further structure that gives a new node the ID of a node the log already holds" in {
      val test = under(stackSuiteNode, NodePlace.Test("pops"))
      val first = TestEventLogEvent.Structure(
        Vector(
          StructureNode.Suite(stackSuiteNode, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
          StructureNode.Test(test, stackSuiteNode, 0, "pops", "- pops")
        )
      )
      val further = TestEventLogEvent.Structure(
        Vector(
          StructureNode.Suite(stackSuiteNode, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
          StructureNode.Test(test, stackSuiteNode, 0, "pushes", "- pushes")
        )
      )

      EventLogComparison.mismatchWhenIs(
        Vector(first, further),
        expected(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pushes", label "- pushes"
          """
        )
      ) shouldBe Some(
        "event 2 of the log is not 'structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    test \"pushes\", label \"- pushes\"': " +
          "its node 2 is not the node an earlier structure held there"
      )
    }
  }

  "Comparing a ScalaTest event" should {
    "report an event of another class" in {
      mismatchOfLastEvent("""test "pops": TestStarting""", """test "pops": TestSucceeded""") shouldBe
        notLastEvent("""test "pops": TestStarting""", "it is a TestSucceeded")
    }

    "report an event assigned to another node" in {
      mismatchOfLastEvent("""test "pops": TestStarting""", """test "pushes": TestStarting""") shouldBe
        notLastEvent("""test "pops": TestStarting""", "it is assigned to another node, test \"pushes\"")
    }

    "report an event assigned to a node where one assigned to the run is expected" in {
      mismatchOfLastEvent("""run: InfoProvided""", """suite "StackSuite": InfoProvided""") shouldBe
        notLastEvent("""run: InfoProvided""", "it is assigned to a node, not to the run")
    }

    "report an event assigned to the run where one assigned to a node is expected" in {
      mismatchOfLastEvent("""suite "StackSuite": InfoProvided""", """run: InfoProvided""") shouldBe
        notLastEvent("""suite "StackSuite": InfoProvided""", "it is assigned to the run, not to a node")
    }

    "report an event of another execution" in {
      mismatchOfLastEvent("""test "pops": TestStarting""", """test "pops" execution 2: TestStarting""") shouldBe
        notLastEvent("""test "pops": TestStarting""", "it is of execution 2, not 1")
    }

    "report another execution of an ancestor than the one the event marks" in {
      mismatchOfLastEvent("""test "pops", in scope "A Stack" execution 1: TestStarting""", """test "pops", in scope "A Stack" execution 2: TestStarting""") shouldBe
        notLastEvent("""test "pops", in scope "A Stack" execution 1: TestStarting""", "it ran in execution 2 of scope \"A Stack\", not 1")
    }

    "match any execution of an ancestor where the event marks none" in {
      mismatchOfLastEvent("""test "pops": TestStarting""", """test "pops", in scope "A Stack" execution 2: TestStarting""") shouldBe None
    }

    "match any execution of an ancestor the event does not mark, beside one it marks" in {
      mismatchOfLastEvent(
        """test "pops", in scope "A Stack" execution 1: TestStarting""",
        """test "pops", in suite "StackSuite" execution 2, in scope "A Stack" execution 1: TestStarting"""
      ) shouldBe None
    }

    "report the highest marked ancestor whose execution differs" in {
      mismatchOfLastEvent(
        """test "pops", in suite "StackSuite" execution 1, in scope "A Stack" execution 1: TestStarting""",
        """test "pops", in suite "StackSuite" execution 2, in scope "A Stack" execution 2: TestStarting"""
      ) shouldBe notLastEvent(
        """test "pops", in suite "StackSuite" execution 1, in scope "A Stack" execution 1: TestStarting""",
        "it ran in execution 2 of suite \"StackSuite\", not 1"
      )
    }

    "compare a marked ancestor's execution at the ancestor's place in the execution path" in {
      val nested =
        """
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
        """
      val expectedEvent = """test "pops", in suite "InnerSuite" execution 1: TestStarting"""

      EventLogComparison.mismatchWhenIs(
        log(nested, """test "pops", in suite "OuterSuite" execution 3, in suite "InnerSuite" execution 2, in scope "A Stack" execution 1: TestStarting"""),
        expected(nested, expectedEvent)
      ) shouldBe Some(s"event 2 of the log is not '$expectedEvent': it ran in execution 2 of suite \"InnerSuite\", not 1")
    }

    "report an execution path with no execution of an ancestor the event marks" in {
      val expectedEvent = """test "pops", in scope "A Stack" execution 1: TestStarting"""
      val logged = log(stackSuite, expectedEvent).map {
        case TestEventLogEvent.ScalaTestEvent(Some(assigned), event) =>
          TestEventLogEvent.ScalaTestEvent(Some(assigned.copy(executionPath = assigned.executionPath.takeRight(1))), event)
        case other => other
      }

      EventLogComparison.mismatchWhenIs(logged, expected(stackSuite, expectedEvent)) shouldBe
        notLastEvent(expectedEvent, "its execution path has no execution of scope \"A Stack\"")
    }

    "match any value of a field the event does not write" in {
      mismatchOfLastEvent(
        """test "pops": TestFailed(throwable: java.lang.IllegalStateException "boom")""",
        """test "pops": TestFailed(message: "another message", analysis: ["analysed"], throwable: java.lang.IllegalStateException "boom", duration: 12, location: SeeStackDepthException, threadName: "worker-3", timeStamp: 1700000000000)"""
      ) shouldBe None
    }

    "report another message than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": InfoProvided(message: "checked size")""", """test "pops": InfoProvided(message: "checked")""") shouldBe
        notLastEvent("""test "pops": InfoProvided(message: "checked size")""", "its message is checked, not checked size")
    }

    "report another text than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": MarkupProvided(text: "*markup*")""", """test "pops": MarkupProvided(text: "plain")""") shouldBe
        notLastEvent("""test "pops": MarkupProvided(text: "*markup*")""", "its text is plain, not *markup*")
    }

    "report another analysis than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": TestFailed(analysis: ["size: 1 -> 0"])""", """test "pops": TestFailed(analysis: [])""") shouldBe
        notLastEvent("""test "pops": TestFailed(analysis: ["size: 1 -> 0"])""", "its analysis is Vector(), not Vector(size: 1 -> 0)")
    }

    "report another duration than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": TestSucceeded(duration: 12)""", """test "pops": TestSucceeded""") shouldBe
        notLastEvent("""test "pops": TestSucceeded(duration: 12)""", "its duration is None, not Some(12)")
    }

    "report another location than the one the event writes" in {
      mismatchOfLastEvent(
        """test "pops": TestStarting(location: LineInFile(7, "StackSuite.scala", None))""",
        """test "pops": TestStarting(location: LineInFile(8, "StackSuite.scala", None))"""
      ) shouldBe notLastEvent(
        """test "pops": TestStarting(location: LineInFile(7, "StackSuite.scala", None))""",
        "its location is Some(LineInFile(8,StackSuite.scala,None)), not Some(LineInFile(7,StackSuite.scala,None))"
      )
    }

    "report another thread name than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": TestStarting(threadName: "worker-3")""", """test "pops": TestStarting(threadName: "worker-4")""") shouldBe
        notLastEvent("""test "pops": TestStarting(threadName: "worker-3")""", "its threadName is worker-4, not worker-3")
    }

    "report another time stamp than the one the event writes" in {
      mismatchOfLastEvent("""test "pops": TestStarting(timeStamp: 1700000000000)""", """test "pops": TestStarting(timeStamp: 1700000000001)""") shouldBe
        notLastEvent("""test "pops": TestStarting(timeStamp: 1700000000000)""", "its timeStamp is 1700000000001, not 1700000000000")
    }

    "report another test count than the one the event writes" in {
      mismatchOfLastEvent("run: RunStarting(testCount: 3)", "run: RunStarting(testCount: 4)") shouldBe
        notLastEvent("run: RunStarting(testCount: 3)", "its testCount is 4, not 3")
    }
  }

  "Comparing a throwable" should {
    "report a logged event with no throwable where one is written" in {
      mismatchOfLastEvent("run: RunAborted(throwable: java.lang.Error)", "run: RunAborted") shouldBe
        notThrowable("java.lang.Error", "it has no throwable")
    }

    "report a throwable of another class" in {
      mismatchOfThrowable("java.lang.Error", "java.lang.Exception") shouldBe
        notThrowable("java.lang.Error", "its throwable's class is java.lang.Exception, not java.lang.Error")
    }

    "match every part the throwable does not write" in {
      mismatchOfThrowable(
        "org.scalatest.exceptions.TestFailedException",
        """org.scalatest.exceptions.TestFailedException "boom" at /src/StackSpec.scala:9 property check frames ["samples.A.f(A.scala:3)"] suppressing [java.lang.Error] caused by java.io.IOException"""
      ) shouldBe None
    }

    "match every part the throwable writes" in {
      val whole =
        """org.scalatest.exceptions.TestFailedException "boom" at /src/StackSpec.scala:9 property check frames ["samples.A.f(A.scala:3)"] suppressing [java.lang.Error "closing" caused by java.lang.Exception] caused by java.io.IOException "inner" caused by java.lang.Error null"""

      mismatchOfThrowable(whole, whole) shouldBe None
    }

    "report another message than the one written" in {
      mismatchOfThrowable("""java.lang.Error "boom"""", """java.lang.Error "bang"""") shouldBe
        notThrowable("""java.lang.Error "boom"""", "its throwable's message is Some(bang), not Some(boom)")
    }

    "report a null message where one is written" in {
      mismatchOfThrowable("""java.lang.Error "boom"""", "java.lang.Error") shouldBe
        notThrowable("""java.lang.Error "boom"""", "its throwable's message is None, not Some(boom)")
    }

    "report a message where a null one is written" in {
      mismatchOfThrowable("java.lang.Error null", """java.lang.Error "bang"""") shouldBe
        notThrowable("java.lang.Error null", "its throwable's message is Some(bang), not None")
    }

    "match a null message where a null one is written" in {
      mismatchOfThrowable("java.lang.Error null", "java.lang.Error") shouldBe None
    }

    "report a throwable that is not a StackDepth where failed code is written" in {
      mismatchOfThrowable("java.lang.Error at StackSpec.scala:9", "java.lang.Error") shouldBe
        notThrowable("java.lang.Error at StackSpec.scala:9", "its throwable is not a StackDepth")
    }

    "report another file name than the one written" in {
      mismatchOfThrowable("java.lang.Error at StackSpec.scala:9", "java.lang.Error at QueueSpec.scala:9") shouldBe
        notThrowable("java.lang.Error at StackSpec.scala:9", "its throwable's file name is Some(QueueSpec.scala), not Some(StackSpec.scala)")
    }

    "report a file name where an undefined one is written" in {
      mismatchOfThrowable("java.lang.Error at ?:9", "java.lang.Error at StackSpec.scala:9") shouldBe
        notThrowable("java.lang.Error at ?:9", "its throwable's file name is Some(StackSpec.scala), not None")
    }

    "report another line number than the one written" in {
      mismatchOfThrowable("java.lang.Error at StackSpec.scala:9", "java.lang.Error at StackSpec.scala:10") shouldBe
        notThrowable("java.lang.Error at StackSpec.scala:9", "its throwable's line number is Some(10), not Some(9)")
    }

    "report a line number where an undefined one is written" in {
      mismatchOfThrowable("java.lang.Error at StackSpec.scala:?", "java.lang.Error at StackSpec.scala:9") shouldBe
        notThrowable("java.lang.Error at StackSpec.scala:?", "its throwable's line number is Some(9), not None")
    }

    "match any pathname where only the file name is written" in {
      mismatchOfThrowable("java.lang.Error at StackSpec.scala:9", "java.lang.Error at /src/StackSpec.scala:9") shouldBe None
    }

    "report another pathname than the one written" in {
      mismatchOfThrowable("java.lang.Error at /src/StackSpec.scala:9", "java.lang.Error at /other/StackSpec.scala:9") shouldBe
        notThrowable(
          "java.lang.Error at /src/StackSpec.scala:9",
          "its throwable's file pathname is Some(/other/StackSpec.scala), not Some(/src/StackSpec.scala)"
        )
    }

    "report a throwable that is no property check failure where one is written" in {
      mismatchOfThrowable("java.lang.Error property check", "java.lang.Error") shouldBe
        notThrowable("java.lang.Error property check", "its throwable is not a property check failure")
    }

    "report other frames than those written" in {
      mismatchOfThrowable(
        """java.lang.Error frames ["samples.A.f(A.scala:3)", "samples.A.g(A.scala:7)"]""",
        """java.lang.Error frames ["samples.A.f(A.scala:3)"]"""
      ) shouldBe notThrowable(
        """java.lang.Error frames ["samples.A.f(A.scala:3)", "samples.A.g(A.scala:7)"]""",
        "its throwable's stack trace is Vector(DecodedStackFrame(samples.A,f,Some(A.scala),Some(3),false,samples.A.f(A.scala:3))), " +
          "not Vector(DecodedStackFrame(samples.A,f,Some(A.scala),Some(3),false,samples.A.f(A.scala:3)), " +
          "DecodedStackFrame(samples.A,g,Some(A.scala),Some(7),false,samples.A.g(A.scala:7)))"
      )
    }

    "report frames where empty brackets are written" in {
      mismatchOfThrowable("java.lang.Error frames []", """java.lang.Error frames ["samples.A.f(A.scala:3)"]""") shouldBe
        notThrowable(
          "java.lang.Error frames []",
          "its throwable's stack trace is Vector(DecodedStackFrame(samples.A,f,Some(A.scala),Some(3),false,samples.A.f(A.scala:3))), not Vector()"
        )
    }

    "report another number of suppressed throwables than those written" in {
      mismatchOfThrowable("java.lang.Error suppressing [java.lang.Exception]", "java.lang.Error") shouldBe
        notThrowable("java.lang.Error suppressing [java.lang.Exception]", "its throwable suppresses 0 throwables, not 1")
    }

    "report a suppressed throwable that differs from the one written at its place" in {
      mismatchOfThrowable(
        """java.lang.Error suppressing [java.lang.Exception "first", java.lang.Exception "second"]""",
        """java.lang.Error suppressing [java.lang.Exception "first", java.lang.Exception "other"]"""
      ) shouldBe notThrowable(
        """java.lang.Error suppressing [java.lang.Exception "first", java.lang.Exception "second"]""",
        "its throwable's suppressed throwable 2's message is Some(other), not Some(second)"
      )
    }

    "report a throwable with no cause where one is written" in {
      mismatchOfThrowable("java.lang.Error caused by java.io.IOException", "java.lang.Error") shouldBe
        notThrowable("java.lang.Error caused by java.io.IOException", "its throwable has no cause")
    }

    "report a cause that differs from the one written, however deep" in {
      mismatchOfThrowable(
        """java.lang.Error caused by java.io.IOException caused by java.lang.Exception "deep"""",
        """java.lang.Error caused by java.io.IOException caused by java.lang.Exception "shallow""""
      ) shouldBe notThrowable(
        """java.lang.Error caused by java.io.IOException caused by java.lang.Exception "deep"""",
        "its throwable's cause's cause's message is Some(shallow), not Some(deep)"
      )
    }

    "compare a throwable that is its own cause only as deep as the throwable written" in {
      val selfCaused = new DecodedThrowable("java.lang.Error", Some("loop"), None, false, Vector.empty)
      selfCaused.link(Some(selfCaused), Vector(selfCaused))
      val logged = TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.RunAborted("loop", Some(selfCaused), None, None, None, "main", 0L))

      EventLogComparison.mismatchWhenIs(
        Vector(logged),
        expected("""run: RunAborted(throwable: java.lang.Error "loop" suppressing [java.lang.Error "loop"] caused by java.lang.Error "loop" caused by java.lang.Error "loop")""")
      ) shouldBe None
    }
  }

  "Comparing a log with the events it is expected to hold in order" should {
    val heldEvents =
      """
      test "pops": TestStarting
      test "pops": TestSucceeded
      """

    "find no mismatch where the log holds each event in that order, with others between them" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        logWithOtherNodeIds(
          "run started",
          stackSuite,
          """
          suite "StackSuite": SuiteStarting
          test "pops": TestStarting
          test "pops": InfoProvided(message: "checked size")
          test "pushes": TestStarting
          test "pops": TestSucceeded
          run ended completed
          """
        ),
        expected(stackSuite, heldEvents)
      ) shouldBe None
    }

    "find no mismatch where no event is expected" in {
      EventLogComparison.mismatchWhenHoldsInOrder(log(wholeRun), Vector.empty) shouldBe None
    }

    "report an event the log does not hold" in {
      EventLogComparison.mismatchWhenHoldsInOrder(log(stackSuite, """test "pops": TestStarting"""), expected(stackSuite, heldEvents)) shouldBe
        Some("the log does not hold 'test \"pops\": TestSucceeded' after the events expected before it")
    }

    "report an event the log holds only before the events expected before it" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        log(
          stackSuite,
          """
          test "pops": TestSucceeded
          test "pops": TestStarting
          """
        ),
        expected(stackSuite, heldEvents)
      ) shouldBe Some("the log does not hold 'test \"pops\": TestSucceeded' after the events expected before it")
    }

    "report an event expected twice that the log holds once" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        log(stackSuite, """test "pops": TestStarting"""),
        expected(
          stackSuite,
          """
          test "pops": TestStarting
          test "pops": TestStarting
          """
        )
      ) shouldBe Some("the log does not hold 'test \"pops\": TestStarting' after the events expected before it")
    }

    "report an event the log holds only for another node" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        log(stackSuite, """test "pushes": TestStarting"""),
        expected(stackSuite, """test "pops": TestStarting""")
      ) shouldBe Some("the log does not hold 'test \"pops\": TestStarting' after the events expected before it")
    }

    "report an event whose written field the log's event does not have" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        log(stackSuite, """test "pops": InfoProvided(message: "checked")"""),
        expected(stackSuite, """test "pops": InfoProvided(message: "checked size")""")
      ) shouldBe Some("the log does not hold 'test \"pops\": InfoProvided(message: \"checked size\")' after the events expected before it")
    }

    "report an event the log holds only after a further structure takes its node out of an ancestor the event marks" in {
      EventLogComparison.mismatchWhenHoldsInOrder(
        log(
          stackSuite,
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops", test name "A Stack pops"
          test "pops": TestStarting
          """
        ),
        expected(stackSuite, """test "pops", in scope "A Stack" execution 1: TestStarting""")
      ) shouldBe Some("the log does not hold 'test \"pops\", in scope \"A Stack\" execution 1: TestStarting' after the events expected before it")
    }

    "report a structure the log does not hold" in {
      EventLogComparison.mismatchWhenHoldsInOrder(log("run started", "run ended completed"), expected(stackSuite)) shouldBe
        Some(s"the log does not hold '$stackSuiteText' after the events expected before it")
    }
  }
}

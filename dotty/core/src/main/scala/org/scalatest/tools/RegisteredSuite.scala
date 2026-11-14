package org.scalatest.tools

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import org.scalatest.EventOrigin
import org.scalatest.Filter
import org.scalatest.RegisteredNode
import org.scalatest.Reporter
import org.scalatest.ScopePlace
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.SuiteRegistrations
import org.scalatest.events.EncodedEvent
import org.scalatest.events.Event
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import scala.jdk.CollectionConverters.*

/** The suite, scope or test of a suite's registration tree that an event is for. */
private[scalatest] enum TreeNode {
  case OfSuite
  case Scope(places: List[ScopePlace])
  case Test(testName: String)
}

/**
 * An event's origin as the emitter keeps it until the event is handed off.
 *
 * @param execution the execution of the event's own node
 * @param executionOfRun the execution of the run that sent the event, which is its parent's execution
 * @param messagesOfTest for an event of a test's execution, the messages the test hands off during it
 */
private[scalatest] final class RecordedOrigin(
  val registration: RegisteredSuite,
  val node: TreeNode,
  val execution: Execution,
  val executionOfRun: Execution,
  val messagesOfTest: Option[TestExecutionMessages] = None
)

/**
 * The messages a test's informer and documenter hand off during one execution of the test, kept in call order among
 * the execution's other events. Each message is assigned as the execution's first written event is. A message is
 * held until that event, and every event of the execution whose origin was recorded before the message, is written.
 * A reporter ahead of every wrapper may hold such an event back.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class TestExecutionMessages {

  private var assigned = Option.empty[NodeExecution]
  // Each entry is an unwritten event of the execution, `Left`, or a held message, `Right`, in call order.
  private var awaited = Vector.empty[Either[Event, EncodedEvent]]

  /** The node and executions of the execution's first written event, once it has been written. */
  def assignment: Option[NodeExecution] = assigned

  /** Whether a message handed off now is written at once: nothing of the execution called before it is unwritten. */
  def writesAtOnce: Boolean = assigned.isDefined && awaited.isEmpty

  /** Holds the messages handed off from now on behind an event of the execution, until that event is written. */
  def awaitWriting(event: Event): Unit = awaited :+= Left(event)

  def hold(message: EncodedEvent): Unit = awaited :+= Right(message)

  /**
   * Takes an event of the execution that is being written, and its assignment where it is the first. Returns the held
   * messages to write before the event, and those to write after it.
   *
   * A closing event releases every held message, before it. Any other event releases, after it, the held messages up
   * to the next unwritten event of the execution.
   */
  def written(event: Event, execution: NodeExecution, closesTheTest: Boolean): (Vector[EncodedEvent], Vector[EncodedEvent]) = {
    if (assigned.isEmpty) {
      assigned = Some(execution)
    }
    val others = awaited.filterNot {
      case Left(unwritten) => unwritten eq event
      case Right(_) => false
    }
    if (closesTheTest) {
      awaited = Vector.empty
      (others.collect { case Right(message) => message }, Vector.empty)
    } else {
      val (released, rest) = others.span(_.isRight)
      awaited = rest
      (Vector.empty, released.collect { case Right(message) => message })
    }
  }
}

/**
 * The origin of the events one engine site sends. The emitter asks it for what to keep beside each of them.
 *
 * @param registration the registration of the instance that sends the events
 */
private[scalatest] abstract class RecordingOrigin(val registration: RegisteredSuite) extends EventOrigin {

  /** Called under the emitter's lock, or under `UnplacedSuites`' lock while the registration is unplaced. */
  def record(): RecordedOrigin
}

/** Where an unplaced instance is placed: with the table of the instance that created it, or below the suite node of the instance that runs it. */
private[scalatest] enum Awaiting {
  case TableOf(creator: RegisteredSuite)
  case Below(outer: RegisteredSuite)

  def registration: RegisteredSuite = {
    this match {
      case TableOf(creator) => creator
      case Below(outer) => outer
    }
  }
}

/** The scopes and tests an unplaced instance's run read, from which its structure is written once it is placed. */
private[scalatest] enum PendingStructure {
  case OfTree(registered: List[RegisteredNode], tags: Map[String, Set[String]], filter: Filter)
  case OfTestNames(testNames: List[String])

  /** The scopes and tests the structure holds, as the filter keeps them. Called under the emitter's lock. */
  def scopesAndTestsFor(table: SuiteNodeTable): List[ScopeOrTest] = {
    this match {
      case OfTree(registered, tags, filter) =>
        SuiteStructure.kept(registered, table.filterKeepingEarlier(SuiteStructure.testNamesIn(registered), testName => filter(testName, tags, table.suiteId)))
      case OfTestNames(testNames) => SuiteStructure.ofTestNames(testNames)
    }
  }
}

/**
 * An event a wrapper of an unplaced instance handed off, held until the instance is placed.
 *
 * @param order the place of the hand-off among every hand-off of an unplaced instance in the process
 * @param messagesHandedOff for a test's closing event whose messages the test handed off one by one, those messages
 */
private[scalatest] final class HeldEvent(
  val order: Long,
  val wrapper: WrapperNode,
  val event: Event,
  val origin: Option[RecordedOrigin],
  val messagesHandedOff: Option[TestExecutionMessages],
  val encoded: Either[Throwable, Vector[EncodedEvent]]
)

/** What placing an instance moves to its emitter: its held events and the origins it recorded for events no wrapper has handed off. */
private[scalatest] final class PlacedEvents(val held: Vector[HeldEvent], val origins: Vector[(Event, RecordedOrigin)])

/**
 * The registration of a suite instance against its suite's node table, in a run that writes a test event log. An
 * instance no site registered against a table is *unplaced*: it has no emitter and no table until an event it sent
 * reaches a structure wrapper of a placed instance. Until then it holds the events its wrappers hand off.
 *
 * While a registration is unplaced its own state is read and written only under `UnplacedSuites`' lock. Placing it
 * takes the emitter's lock, then that one.
 *
 * @param runOfCreator for an instance created by another instance of the suite to run one test, the execution of the
 *                     creator's run. The instance's run uses it instead of starting its own.
 * @param awaiting for an unplaced instance a site of another unplaced instance registered, where it is placed
 */
private[scalatest] final class RegisteredSuite private (
  instance: Suite,
  placedAtRegistration: Option[RegisteredSuite.Placement],
  runOfCreator: Option[Execution],
  awaiting: Option[Awaiting]
) extends SuiteRegistration {

  /**
   * @param ofTopLevelSuite the registration of the top-level suite this instance runs within, or none where it is that suite
   */
  def this(emitter: TestEventLogEmitter, table: SuiteNodeTable, instance: Suite, ofTopLevelSuite: Option[RegisteredSuite], runOfCreator: Option[Execution]) =
    this(instance, Some(RegisteredSuite.Placement(emitter, table, ofTopLevelSuite)), runOfCreator, None)

  @volatile private var placement = placedAtRegistration

  // All four are read and written only under the emitter's lock once placed. Both collections hold events by identity:
  //  - the set holds the events inner wrappers of this top-level suite's run handed off that had not reached its
  //    wrapper when this registration was removed;
  //  - the map holds each closing event of a test of this run whose messages the test handed off one by one, with
  //    those messages, until a wrapper hands the event off. While this registration is unplaced, it holds the closing
  //    events of its own tests.
  // Both live here, not in the emitter, so that they are dropped with the top-level suite's wrapper. Removing a
  // registration drops neither, so a closing event sent or passed on after the removal is still written without its
  // recorded messages.
  private var executionOfLatestRun = runOfCreator
  private var isRemoved = false
  private val handedOffBeforeRemoval = Collections.newSetFromMap(new IdentityHashMap[Event, java.lang.Boolean])
  private val closingEventsWithMessagesHandedOff = new IdentityHashMap[Event, TestExecutionMessages]

  // Read and written only under `UnplacedSuites`' lock, and only while unplaced.
  private var held = Vector.empty[HeldEvent]
  private val origins = new IdentityHashMap[Event, RecordedOrigin]
  private var awaitedBy = List.empty[RegisteredSuite]
  private var placedBelow = Option.empty[RegisteredSuite]
  private var suiteIdRead = Option.empty[String]
  private var suiteNameRead = Option.empty[String]
  private var pendingStructure = Option.empty[PendingStructure]
  private var firstRunWhileUnplaced = Option.empty[Execution]
  private var unencodable = Option.empty[Throwable]

  def isPlaced: Boolean = placement.isDefined

  /** The emitter of a placed instance. */
  def emitter: TestEventLogEmitter = placement.get.emitter

  /** The node table of a placed instance. */
  def table: SuiteNodeTable = placement.get.table

  /** The registration of the top-level suite a placed instance runs within: its own where it is that suite. */
  def topLevel: RegisteredSuite = placement.get.ofTopLevelSuite.getOrElse(this)

  // Runs `unplaced` under `UnplacedSuites`' lock where this registration is unplaced, and returns `None` where it is placed.
  private def whileUnplaced[A](unplaced: => A): Option[A] = UnplacedSuites.synchronized(if (placement.isEmpty) Some(unplaced) else None)

  /** Starts an execution for a run of the instance. Called under the emitter's lock, or `UnplacedSuites`' while unplaced. */
  def startRunUnderLock(): Execution = {
    val execution = runOfCreator.getOrElse {
      placement match {
        case Some(placed) => placed.table.takeExecutionForStartingRun()
        case None =>
          val started = new Execution
          if (firstRunWhileUnplaced.isEmpty) {
            firstRunWhileUnplaced = Some(started)
          }
          started
      }
    }
    executionOfLatestRun = Some(execution)
    execution
  }

  /** The execution of the instance's latest run, starting one where no site has. Called under the lock `startRunUnderLock` is. */
  def startRunUnlessStarted(): Execution = executionOfLatestRun.getOrElse(startRunUnderLock())

  /** Marks this registration removed. Called under the emitter's lock. */
  def markRemoved(): Unit = isRemoved = true

  /** Whether this registration has been removed. Called under the emitter's lock. */
  def hasBeenRemoved: Boolean = isRemoved

  /**
   * Keeps an event an inner wrapper of this top-level suite's run handed off, which had not reached this suite's
   * wrapper when this registration was removed. Called under the emitter's lock.
   */
  def keepHandedOffBeforeRemoval(event: Event): Unit = handedOffBeforeRemoval.add(event)

  /**
   * Stops keeping an event an inner wrapper handed off before this registration was removed, and returns whether it
   * was kept. Called under the emitter's lock.
   */
  def takeHandedOffBeforeRemoval(event: Event): Boolean = handedOffBeforeRemoval.remove(event)

  /**
   * Keeps a closing event of a test of this top-level suite's run, whose messages the test handed off one by one,
   * with those messages. Called under the emitter's lock.
   */
  def keepClosingEventWithMessagesHandedOff(event: Event, messages: TestExecutionMessages): Unit =
    closingEventsWithMessagesHandedOff.put(event, messages)

  /**
   * Stops keeping a closing event whose messages its test handed off one by one, and returns those messages where it
   * was kept. Called under the emitter's lock.
   */
  def takeClosingEventWithMessagesHandedOff(event: Event): Option[TestExecutionMessages] =
    Option(closingEventsWithMessagesHandedOff.remove(event))

  def startRun(): Unit = whileUnplaced(startRunUnderLock()).fold(emitter.startRun(this))(_ => ())

  def originAt(scopes: List[ScopePlace]): EventOrigin =
    new OriginInRun(if (scopes.isEmpty) TreeNode.OfSuite else TreeNode.Scope(scopes))

  def startTestExecution(testName: String): EventOrigin = {
    if (isPlaced) {
      emitter.addTestWhereLacking(table, testName, instance.scopesAndTestsOfRegistrationTree)
    }
    new OriginOfTestExecution(testName)
  }

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, testName), handOff)

  def reporterAheadOf(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.SuiteAheadOfSorter(this), handOff)

  def reporterOfSuite(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.SelfRegisteredSuite(this), handOff)

  def registerInstanceOfTest(instance: Suite): SuiteRegistration = {
    val registration = whileUnplaced(new RegisteredSuite(instance, None, Some(startRunUnlessStarted()), Some(Awaiting.TableOf(this))))
      .getOrElse(new RegisteredSuite(emitter, table, instance, Some(topLevel), Some(emitter.startRunUnlessStarted(this))))
    added(instance, registration)
  }

  def registerInstanceRunInItsPlace(instance: Suite): SuiteRegistration = {
    val registration = whileUnplaced(new RegisteredSuite(instance, None, None, Some(Awaiting.TableOf(this))))
      .getOrElse(new RegisteredSuite(emitter, table, instance, Some(topLevel), None))
    added(instance, registration)
  }

  // An unplaced registration awaiting this one is placed when this one is.
  private def added(instance: Suite, registration: RegisteredSuite): RegisteredSuite = {
    if (!registration.isPlaced) {
      UnplacedSuites.synchronized { awaitedBy = registration :: awaitedBy }
    }
    RegisteredSuites.add(instance, registration)
    registration
  }

  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = {
    instance.scopesAndTestsOfRegistrationTree.foreach { registered =>
      val tags = Suite.autoTagClassAnnotations(tagsOfTests, instance, SuiteStructure.testNamesIn(registered))
      whileUnplaced { pendingStructure = Some(PendingStructure.OfTree(registered, tags, filter)) }.getOrElse {
        emitter.writeStructureOfTreeWhereChanged(table, registered, testName => filter(testName, tags, table.suiteId))
      }
    }
  }

  def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit = {
    val testNames = keptTestNames.toList
    whileUnplaced { pendingStructure = Some(PendingStructure.OfTestNames(testNames)) }.getOrElse {
      emitter.writeStructureWhereChanged(table, SuiteStructure.ofTestNames(testNames))
    }
  }

  def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = {
    whileUnplaced(new RegisteredSuite(nestedSuite, None, None, Some(Awaiting.Below(this)))) match {
      case Some(unplaced) =>
        new StructureWrapper(reporter, WrapperNode.NestedSuite(added(nestedSuite, unplaced)), unplaced.handOff)
      case None =>
        table.nestedSuites.lift(position) match {
          case Some(tableOfNestedSuite) =>
            val registration = new RegisteredSuite(emitter, tableOfNestedSuite, nestedSuite, Some(topLevel), None)
            RegisteredSuites.add(nestedSuite, registration)
            new StructureWrapper(reporter, WrapperNode.NestedSuite(registration), registration.handOff)
          case None => reporter
        }
    }
  }

  def suiteNameRead(suiteName: String): Unit = whileUnplaced(if (suiteNameRead.isEmpty) suiteNameRead = Some(suiteName))

  def suiteIdRead(suiteId: String): Unit = whileUnplaced(if (suiteIdRead.isEmpty) suiteIdRead = Some(suiteId))

  def remove(): Unit = {
    RegisteredSuites.remove(instance, this)
    whileUnplaced(dropWhileUnplaced()).getOrElse(emitter.forget(this))
  }

  // The suite's own `run` may have built the Status, so `whenCompleted` may be user code. Where it throws, nothing
  // will call back, so the registration is removed at once.
  def removeWhenCompleted(status: Status): Unit = {
    try status.whenCompleted(_ => remove())
    catch {
      case thrown: Throwable =>
        remove()
        TestEventLogEmitter.keptFromCaller(throw thrown)
    }
  }

  /** Hands off an event one of this instance's structure wrappers received: to the emitter once placed, and otherwise held. */
  def handOff(wrapper: WrapperNode, event: Event): Unit = {
    placement match {
      case Some(placed) => placed.emitter.writeSuiteEvent(wrapper, event)
      case None => holdUnlessPlaced(wrapper, event)
    }
  }

  // Encoding calls the event's own throwables, so it is done outside every lock, once it is known how to encode.
  private def holdUnlessPlaced(wrapper: WrapperNode, event: Event): Unit = {
    val toEncode = UnplacedSuites.synchronized {
      if (isPlaced) {
        Left(true)
      } else if (isRemoved || UnplacedSuites.heldByAnInnerWrapper(event, this)) {
        Left(false)
      } else {
        Right(UnplacedSuites.originOwnerOf(event).exists(_.closingEventsWithMessagesHandedOff.containsKey(event)))
      }
    }
    toEncode match {
      case Left(isPlacedNow) =>
        if (isPlacedNow) {
          emitter.writeSuiteEvent(wrapper, event)
        }
      case Right(messagesHandedOff) =>
        val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
        val held = UnplacedSuites.synchronized {
          if (isPlaced) {
            false
          } else {
            if (!isRemoved && !UnplacedSuites.heldByAnInnerWrapper(event, this)) {
              hold(wrapper, event, encoded)
            }
            true
          }
        }
        if (!held) {
          emitter.writeSuiteEvent(wrapper, event)
        }
    }
  }

  // Called under `UnplacedSuites`' lock. An event whose origin another unplaced instance recorded is that instance's,
  // which is then placed with this one.
  private def hold(wrapper: WrapperNode, event: Event, encoded: Either[Throwable, Vector[EncodedEvent]]): Unit = {
    val owner = UnplacedSuites.originOwnerOf(event)
    owner.filterNot(_ eq this).foreach(_.awaitPlacementBelow(this))
    val origin = owner.flatMap(recorder => Option(recorder.origins.remove(event)))
    val messagesHandedOff = owner.flatMap(recorder => Option(recorder.closingEventsWithMessagesHandedOff.remove(event)))
    if (wrapper.isInstanceOf[WrapperNode.NestedSuite] && TestEventLogEmitter.isSuiteEvent(event)) {
      learnNames(event)
    }
    held :+= new HeldEvent(UnplacedSuites.nextOrder(), wrapper, event, origin, messagesHandedOff, encoded)
    UnplacedSuites.keepHeld(event, this)
  }

  /**
   * Where this unplaced instance awaits no other, has it placed below `outer`, an unplaced instance whose wrapper one
   * of its events reached. Called under `UnplacedSuites`' lock.
   */
  def awaitPlacementBelow(outer: RegisteredSuite): Unit = {
    if (awaiting.isEmpty && placedBelow.isEmpty && !(outer eq this) && !isPlaced) {
      placedBelow = Some(outer)
      outer.awaitedBy = this :: outer.awaitedBy
    }
  }

  /** What this unplaced instance awaits, if anything: the registration it is placed with or below. Called under `UnplacedSuites`' lock. */
  def awaited: Option[Awaiting] = awaiting.orElse(placedBelow.map(Awaiting.Below(_)))

  // Called under `UnplacedSuites`' lock: the names of an event of this instance's own, which its run's sites read.
  private def learnNames(event: Event): Unit = {
    TestEventLogEmitter.namesIn(event).foreach { names =>
      if (suiteIdRead.isEmpty) {
        suiteIdRead = Some(names.suiteId)
      }
      if (suiteNameRead.isEmpty) {
        suiteNameRead = Some(names.suiteName)
      }
    }
  }

  /** Records an event's origin: with the emitter once placed, and otherwise until this instance is placed. */
  private def recordOrigin(event: Event, origin: RecordingOrigin, messages: Option[TestExecutionMessages]): Unit = {
    whileUnplaced {
      if (!isRemoved) {
        messages.foreach(closingEventsWithMessagesHandedOff.put(event, _))
        val recorded = origin.record()
        origins.put(event, recorded)
        UnplacedSuites.keepOrigin(event, this)
        recorded.messagesOfTest.foreach(_.awaitWriting(event))
        learnNames(event)
      }
    }.getOrElse {
      messages match {
        case Some(handedOff) => emitter.recordOriginOfClosingEventWithMessagesHandedOff(event, origin, handedOff)
        case None => emitter.recordOrigin(event, origin)
      }
    }
  }

  // Runs under the test's recorder lock, so it neither builds nor encodes: the message comes encoded. While this
  // registration is unplaced, the message is held until its test's execution is written.
  private def writeTestMessage(messages: TestExecutionMessages, encoded: Either[Throwable, EncodedEvent]): Unit = {
    whileUnplaced {
      if (!isRemoved) {
        encoded.fold(thrown => if (unencodable.isEmpty) unencodable = Some(thrown), messages.hold)
      }
    }.getOrElse(emitter.writeTestMessage(this, messages, encoded))
  }

  // Called under `UnplacedSuites`' lock.
  private def dropWhileUnplaced(): Unit = {
    isRemoved = true
    held.foreach(one => UnplacedSuites.forget(one.event))
    origins.keySet.forEach(UnplacedSuites.forget(_))
    held = Vector.empty
    origins.clear()
    closingEventsWithMessagesHandedOff.clear()
  }

  /** The suite ID this unplaced instance's run read, once a site has read it. Called under `UnplacedSuites`' lock. */
  def suiteIdOfRun: Option[String] = suiteIdRead

  /** Whether this unplaced instance can be placed. Called under `UnplacedSuites`' lock. */
  def isPlaceable: Boolean = !isRemoved && (awaiting.exists(_.isInstanceOf[Awaiting.TableOf]) || suiteIdRead.isDefined)

  /** The unplaced instances that are placed with or below this one. Called under `UnplacedSuites`' lock. */
  def awaitingThisOne: List[RegisteredSuite] = awaitedBy.reverse

  /** The suite name the instance's run read, where a site has read it, else its suite ID. Called under `UnplacedSuites`' lock. */
  def suiteNameOfRun: String = suiteNameRead.orElse(suiteIdRead).getOrElse("")

  /** Whether this registration's own instance is `suite`. */
  def isOf(suite: Suite): Boolean = instance eq suite

  /**
   * Mints a suite node for this unplaced instance, nested directly in `parent`, with the suite ID and name its run
   * read and its own class name. Called under the emitter's lock and `UnplacedSuites`' lock.
   */
  def mintedBelow(parent: SuiteNodeTable, mint: () => NodeId): SuiteNodeTable =
    parent.mintNestedSuite(Some(instance), suiteNameOfRun, suiteIdRead.getOrElse(parent.suiteId), Suite.getSuiteClassName(instance), mint)

  /**
   * Places this unplaced instance: from now on its events go to `emitter`, assigned to the nodes of `table`. Returns
   * the events it held and the origins it recorded, and the structure its run read, if any. The run it started while
   * unplaced takes the execution the suite's `SuiteStarting` opened, where no run has taken it. Called under the
   * emitter's lock and `UnplacedSuites`' lock.
   */
  def place(emitter: TestEventLogEmitter, table: SuiteNodeTable, ofTopLevelSuite: RegisteredSuite): (PlacedEvents, Option[PendingStructure], Option[Throwable]) = {
    placement = Some(RegisteredSuite.Placement(emitter, table, Some(ofTopLevelSuite)))
    closingEventsWithMessagesHandedOff.forEach((event, messages) => ofTopLevelSuite.keepClosingEventWithMessagesHandedOff(event, messages))
    closingEventsWithMessagesHandedOff.clear()
    if (runOfCreator.isEmpty) {
      firstRunWhileUnplaced.foreach(run => table.takeRunOpenedBySuiteStarting().foreach(table.executionsOf(table.node).countAsOne(run, _)))
    }
    val placed = new PlacedEvents(held, origins.entrySet.asScala.toVector.map(entry => (entry.getKey, entry.getValue)))
    held.foreach(one => UnplacedSuites.forget(one.event))
    origins.keySet.forEach(UnplacedSuites.forget(_))
    held = Vector.empty
    origins.clear()
    (placed, pendingStructure, unencodable)
  }

  // The origin of the events of the suite itself or of one of its scopes, each of which takes the run's execution.
  private final class OriginInRun(node: TreeNode) extends RecordingOrigin(RegisteredSuite.this) {

    def recordFor(event: Event): Unit = recordOrigin(event, this, None)

    def record(): RecordedOrigin = {
      val execution = startRunUnlessStarted()
      new RecordedOrigin(RegisteredSuite.this, node, execution, execution)
    }
  }

  // The origin of the events of one execution of a test, which ran in the run in flight when its first event was sent.
  private final class OriginOfTestExecution(testName: String) extends RecordingOrigin(RegisteredSuite.this) {

    private val execution = new Execution
    private val messages = new TestExecutionMessages
    // Read and written only under the emitter's lock, or `UnplacedSuites`' while unplaced.
    private var executionOfItsRun = Option.empty[Execution]

    def recordFor(event: Event): Unit = recordOrigin(event, this, None)

    def record(): RecordedOrigin = {
      val ofRun = executionOfItsRun.getOrElse(startRunUnlessStarted())
      executionOfItsRun = Some(ofRun)
      new RecordedOrigin(RegisteredSuite.this, TreeNode.Test(testName), execution, ofRun, Some(messages))
    }

    override def preparedHandOffOfMessage(message: => Event): () => Unit = {
      val encoded = TestEventLogEmitter.encodedTestMessage(message)
      () => writeTestMessage(messages, encoded)
    }

    override def ofClosingEventWithMessagesHandedOff: EventOrigin = {
      val ofTestExecution = this
      new RecordingOrigin(RegisteredSuite.this) {
        def recordFor(event: Event): Unit = recordOrigin(event, ofTestExecution, Some(messages))
        def record(): RecordedOrigin = ofTestExecution.record()
      }
    }
  }
}

private[scalatest] object RegisteredSuite {

  /** Where a placed instance's events go: its emitter, its node table, and the top-level suite it runs within, if not itself. */
  final case class Placement(emitter: TestEventLogEmitter, table: SuiteNodeTable, ofTopLevelSuite: Option[RegisteredSuite])

  /** A new unplaced registration of an instance no site has registered against a table. The caller adds it to `RegisteredSuites`. */
  def unplaced(instance: Suite): RegisteredSuite = new RegisteredSuite(instance, None, None, None)
}

/**
 * The events and origins every unplaced instance of the process holds, by event. An unplaced instance belongs to no
 * emitter until it is placed, so this state belongs to the process. Its lock is taken under an emitter's lock, never
 * the other way round.
 */
private[scalatest] object UnplacedSuites {

  // Read and written only under this object's lock. Both are keyed by the event's identity.
  private val heldBy = new IdentityHashMap[Event, RegisteredSuite]
  private val originRecordedBy = new IdentityHashMap[Event, RegisteredSuite]
  private var handOffs = 0L

  def nextOrder(): Long = {
    handOffs += 1
    handOffs
  }

  def keepHeld(event: Event, registration: RegisteredSuite): Unit = {
    originRecordedBy.remove(event)
    heldBy.put(event, registration)
  }

  def keepOrigin(event: Event, registration: RegisteredSuite): Unit = originRecordedBy.put(event, registration)

  def forget(event: Event): Unit = {
    heldBy.remove(event)
    originRecordedBy.remove(event)
  }

  /** The unplaced instance that recorded the event's origin, where no wrapper has handed it off. */
  def originOwnerOf(event: Event): Option[RegisteredSuite] = Option(originRecordedBy.get(event))

  /**
   * Whether an unplaced instance holds the event, handed off by one of its wrappers. Where that instance is not
   * `outer`, whose wrapper the event now reaches, it is placed with `outer`.
   */
  def heldByAnInnerWrapper(event: Event, outer: RegisteredSuite): Boolean = {
    Option(heldBy.get(event)) match {
      case Some(holder) =>
        holder.awaitPlacementBelow(outer)
        true
      case None => false
    }
  }

  /** The unplaced instance whose event this is: the one that holds it, or that recorded its origin. */
  def ownerOf(event: Event): Option[RegisteredSuite] = Option(heldBy.get(event)).orElse(originOwnerOf(event))

  /** Whether an unplaced instance holds the event, rather than only having recorded its origin. */
  def holds(event: Event): Boolean = heldBy.containsKey(event)
}

/**
 * The registrations of this process's suite instances, by instance. A suite instance cannot carry its registration,
 * and no argument reaches every site that sends its events, so every run that writes a test event log registers here.
 *
 * A lookup takes no lock: every test of every run of the process makes one. Registering, removing and counting the
 * open event logs take this object's lock.
 */
private[scalatest] object RegisteredSuites extends SuiteRegistrations {

  // A suite instance as a key: a suite's own `equals` and `hashCode` are user code.
  private final class Identity(val instance: Suite) {
    override def hashCode: Int = System.identityHashCode(instance)

    override def equals(other: Any): Boolean = {
      other match {
        case identity: Identity => identity.instance eq instance
        case _ => false
      }
    }
  }

  // Each value is immutable, and replaced only under this object's lock.
  private val registrationsOf = new ConcurrentHashMap[Identity, List[RegisteredSuite]]
  // Read and written only under this object's lock.
  private var openEventLogs = 0

  /** Counts an emitter whose event log has opened. The first makes every site look its suite instances up here. */
  def eventLogOpened(): Unit = synchronized {
    openEventLogs += 1
    if (openEventLogs == 1) {
      SuiteRegistration.lookUpIn(this)
    }
  }

  /** Stops counting an emitter whose event log has closed. After the last, every site finds no registration again. */
  def eventLogClosed(): Unit = synchronized {
    openEventLogs -= 1
    if (openEventLogs == 0) {
      SuiteRegistration.lookUpNowhere()
    }
  }

  def add(instance: Suite, registration: RegisteredSuite): Unit = synchronized {
    registrationsOf.put(new Identity(instance), registration :: registered(instance))
  }

  def remove(instance: Suite, registration: RegisteredSuite): Unit = synchronized {
    registered(instance).filterNot(_ eq registration) match {
      case Nil => registrationsOf.remove(new Identity(instance))
      case remaining => registrationsOf.put(new Identity(instance), remaining)
    }
  }

  def of(suite: Suite): SuiteRegistration = {
    registered(suite) match {
      case registration :: Nil => registration
      case _ => SuiteRegistration.none
    }
  }

  def registeredUnlessRegistered(suite: Suite): Option[SuiteRegistration] = synchronized {
    if (registered(suite).isEmpty) {
      val registration = RegisteredSuite.unplaced(suite)
      add(suite, registration)
      Some(registration)
    } else {
      None
    }
  }

  private def registered(instance: Suite): List[RegisteredSuite] =
    Option(registrationsOf.get(new Identity(instance))).getOrElse(Nil)
}

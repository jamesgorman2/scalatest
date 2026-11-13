package org.scalatest.tools

import org.scalatest.Reporter
import org.scalatest.events.Event

/**
 * The reporter a suite is given in a run that writes a test event log. It hands off each event it receives, naming
 * its suite's node table, then passes the event unchanged to the reporter it wraps.
 *
 * Whatever the hand-off throws is kept from the reporter's caller as `TestEventLogEmitter.runEvent` keeps what it
 * throws, so the wrapped reporter misses an event only where the hand-off throws a `VirtualMachineError` other than
 * a `StackOverflowError`, or a `ThreadDeath`.
 */
private[scalatest] final class StructureWrapper(
  wrapped: Reporter,
  table: SuiteNodeTable,
  handOff: (SuiteNodeTable, Event) => Unit
) extends Reporter {

  def apply(event: Event): Unit = {
    TestEventLogEmitter.keptFromCaller(handOff(table, event))
    wrapped(event)
  }
}

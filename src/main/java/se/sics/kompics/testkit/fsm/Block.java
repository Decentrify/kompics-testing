package se.sics.kompics.testkit.fsm;

import se.sics.kompics.KompicsEvent;
import se.sics.kompics.testkit.BlockInit;
import se.sics.kompics.testkit.Testkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

class Block {

  // // TODO: 2/17/17 make this private
  final int times;
  private final int startState;
  private int currentCount;
  private BlockInit blockInit, iterationInit;

  private Set<EventSpec<? extends KompicsEvent>> disallowed;
  private Set<EventSpec<? extends KompicsEvent>> allowed;
  private Set<EventSpec<? extends KompicsEvent>> dropped;
  Block previousBlock;

  private List<Spec> expected = new ArrayList<Spec>();
  private List<Spec> pending = new ArrayList<Spec>();
  private List<Spec> received = new ArrayList<Spec>();

  enum MODE { HEADER, BODY, UNORDERED }
  MODE mode = MODE.HEADER;

  Block(Block previousBlock, int times, int startState, BlockInit blockInit) {
    this(previousBlock, times, startState);
    this.blockInit = blockInit;
  }

  Block(Block previousBlock, int times, int startState) {
    this.times = times;
    this.startState = startState;
    this.previousBlock = previousBlock;

    if (previousBlock == null) {
      initEmptyBlock();
    } else {
      this.disallowed = new HashSet<>(previousBlock.disallowed);
      this.allowed = new HashSet<>(previousBlock.allowed);
      this.dropped = new HashSet<>(previousBlock.dropped);
    }
  }

  void initialize() {
    currentCount = times;

    if (blockInit != null) {
      blockInit.init();
    }

    runIterationInit();
  }

  void setIterationInit(BlockInit iterationInit) {
    this.iterationInit = iterationInit;
  }

  int getCurrentCount() {
    return currentCount;
  }

  int getStartState() {
    return startState;
  }

  void iterationComplete() {
    currentCount--;

    if (hasMoreIterations()) {
      runIterationInit();
    }
  }

  boolean hasMoreIterations() {
    return currentCount > 0;
  }

  int indexOfFirstState() {
    return startState + 1;
  }

  void expectWithinBlock(Spec spec) {
    expected.add(spec);
  }

  private void runIterationInit() {
    if (iterationInit != null) {
      iterationInit.init();
    }

    pending.clear();
    received.clear();
    for (Spec spec : expected) {
      pending.add(spec);
    }
  }

  boolean handle(EventSpec<? extends KompicsEvent> receivedSpec) {
    //Testkit.logger.info("block try handle {}, {}", receivedSpec, pendingEventsToString());
    for (Iterator<Spec> iterator = pending.iterator(); iterator.hasNext();) {
      Spec spec = iterator.next();
      if (spec.match(receivedSpec)) {
        received.add(spec);
        iterator.remove();
        Testkit.logger.trace("block handling {}, {}", receivedSpec, pendingEventsToString());
        receivedSpec.handle();
        return true;
      }
    }
    return previousBlock != null && previousBlock.handle(receivedSpec);
  }

  boolean hasPendingEvents() {
    return !pending.isEmpty();
  }

  String pendingEventsToString() {
    StringBuilder sb = new StringBuilder("Pending<");
    for (Spec spec : pending) {
      sb.append("(").append(spec).append(") ");
    }
    sb.append(">");
    return sb.toString();
  }

  String status() {
    StringBuilder sb = new StringBuilder("Block<Received(");
    for (Spec spec : received) {
      sb.append(spec).append(" ");
    }
    sb.append(")Pending(");
    for (Spec spec : pending) {
      sb.append(spec).append(" ");
    }
    sb.append(")>");
    return sb.toString();
  }

  private void initEmptyBlock() {
    disallowed = new HashSet<>();
    allowed = new HashSet<>();
    dropped = new HashSet<>();
  }

  void addDisallowedMessage(EventSpec<? extends KompicsEvent> eventSpec) {
    if (disallowed.add(eventSpec)) {
      allowed.remove(eventSpec);
      dropped.remove(eventSpec);
    }
  }

  void addAllowedMessage(EventSpec<? extends KompicsEvent> eventSpec) {
    if (allowed.add(eventSpec)) {
      disallowed.remove(eventSpec);
      dropped.remove(eventSpec);
    }
  }

  void addDroppedMessage(EventSpec<? extends KompicsEvent> eventSpec) {
    if (dropped.add(eventSpec)) {
      disallowed.remove(eventSpec);
      allowed.remove(eventSpec);
    }
  }

  Collection<EventSpec<? extends KompicsEvent>> getDisallowedEvents() {
    return disallowed;
  }
  Collection<EventSpec<? extends KompicsEvent>> getAllowedEvents() {
    return allowed;
  }
  Collection<EventSpec<? extends KompicsEvent>> getDroppedEvents() {
    return dropped;
  }

}

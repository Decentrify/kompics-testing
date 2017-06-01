/**
 * This file is part of the Kompics Testing runtime.
 *
 * Copyright (C) 2017 Swedish Institute of Computer Science (SICS)
 * Copyright (C) 2017 Royal Institute of Technology (KTH)
 *
 * Kompics is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.sics.kompics.testing;

import com.google.common.base.Function;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import se.sics.kompics.KompicsEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

class NFA {
  private int stateIDs = 0;

  private final Comparator<Class<? extends KompicsEvent>> eventComparator = new Comparator<Class<? extends KompicsEvent>>() {
    @Override
    public int compare(Class<? extends KompicsEvent> e1, Class<? extends KompicsEvent> e2) {
      if (e1 == e2) {
        return 0;
      } else if (e1.isAssignableFrom(e2)) {
        return 1;
      } else {
        return -1;
      }
    }
  };

  private final Map<Class<? extends KompicsEvent>, Function<? extends KompicsEvent, Action>> defaultActions =
      new TreeMap<Class<? extends KompicsEvent>, Function<? extends KompicsEvent, Action>>(eventComparator);

  private RepeatFA repeatMain;
  private Set<State> currentStates = new HashSet<State>();
  private final State errorState = new State(nextid(), null);
  private Logger logger = TestContext.logger;
  private FA currentFA;
  private Stack<FA> previousFA = new Stack<FA>();
  private HashSet<Block> activeBlocks = new HashSet<Block>();

  NFA(Block initialBlock) {
    repeatMain = new RepeatFA(1, initialBlock);
    currentFA = repeatMain;
    previousFA.push(repeatMain);
  }

  private int nextid() {
    return stateIDs++;
  }

  <E extends KompicsEvent> void setDefaultAction(Class<E> eventType, Function<E, Action> predicate) {
    defaultActions.put(eventType, predicate);
  }

  private Action defaultLookup(EventSpec receivedSpec) {
    if (receivedSpec == null) {
      return null;
    }

    KompicsEvent event = receivedSpec.getEvent();
    Class<? extends KompicsEvent> eventType = event.getClass();

    for (Class<? extends KompicsEvent> registeredType : defaultActions.keySet()) {
      if (registeredType.isAssignableFrom(eventType)) {
        return actionFor(event, registeredType);
      }
    }
    return null;
  }

  private <E extends KompicsEvent> Action actionFor(
      KompicsEvent event, Class<? extends KompicsEvent> registeredType) {
    Function<E, Action> function = (Function<E, Action>) defaultActions.get(registeredType);
    Action action = function.apply((E) event);
    if (action == null) {
      throw new NullPointerException(String.format("(default handler for %s returned null for event '%s')",
          registeredType, event));
    }
    return action;
  }

  void addSpec(Spec spec) {
    currentFA.addSpec(spec);
  }

  void addRepeat(int count, Block block) {
    if (count == Block.STAR) {
      currentFA = new KleeneFA(block);
    } else {
      currentFA = new RepeatFA(count, block);
    }
    previousFA.push(currentFA);
  }

  void endRepeat() {
    FA child = previousFA.pop();
    assert child == currentFA;
    if (previousFA.isEmpty()) {
      throw new IllegalStateException("no matching block for end repeat");
    }
    currentFA.end();
    currentFA = previousFA.peek(); // previous
    currentFA.addFA(child);
  }

  void either(Block block) {
    currentFA = new ConditionalFA(block);
    previousFA.push(currentFA);
  }

  void or() {
    if (!(currentFA instanceof ConditionalFA)) {
      throw new IllegalStateException("Not in conditional mode");
    }
    ((ConditionalFA) currentFA).or();
  }

  void build() {
    assert repeatMain == currentFA;
    assert repeatMain == previousFA.pop();
    assert  previousFA.isEmpty();
    repeatMain.end();
    BaseFA finalFA = new BaseFA(repeatMain.block);
    finalFA.startState.isFinalState = true;
    repeatMain.build(finalFA);
    currentStates = new HashSet<State>(repeatMain.startState.eclosure());
    logger.debug("start state is {}", currentStates);
    for (State s : currentStates) {
      logger.debug("{}", s.show());
    }
    logger.debug("final state is {}", finalFA.startState);
  }

  boolean isInFinalState() {
    for (State state : currentStates) {
      if (state.isFinalState) {
        return true;
      }
    }
    return false;
  }

  boolean doTransition(EventSpec receivedSpec) {
    logger.debug("{}: received event {}", currentStates, receivedSpec);
    while (true) {
      tryInternalEventTransitions();

      Set<State> nextStates = new HashSet<State>();
      if (receivedSpec != null) {
        tryStateTransitions(receivedSpec, nextStates);
      }

      if (!nextStates.isEmpty()) {
        return true;
      } else {
        // check if any current state is an internal action
        // if found kill those that aren't and retry handle received spec
        forceInternalEventTransitions(nextStates);
        if (!nextStates.isEmpty()) {
          logger.debug("internal transition(s) found");
          updateCurrentState(nextStates);

          if (receivedSpec == null) { // retry event queue
            return true;
          } else {
            continue;
          }
        }

        logger.debug("No internal transition(s) found");
        logger.debug("trying e-transitions");
      }

      // try registered default actions
      boolean handleByDefault = tryDefaultActions(receivedSpec);
      if (handleByDefault) {
        return true;
      }

      if (receivedSpec == null) {
        logger.debug("No event was received");
      } else {
        logger.error("No transitions found for {}", receivedSpec);
        logger.debug("Last state was {}", currentStates);
      }
      return false;
    }
  }

  private void tryStateTransitions(EventSpec receivedSpec, Set<State> nextStates) {
    Set<Transition> transitions = new HashSet<Transition>();
    // for each current state, get next state for spec
    for (State state : currentStates) {
      Collection<Transition> t = state.getTransition(receivedSpec);
      for (Transition transition : t) {
        nextStates.add(transition.nextState);
        transitions.add(transition);
      }
    }

    if (!nextStates.isEmpty()) {
      //logger.debug("{} some transitions were found from current state", currentStates);
      // kill threads without transitions and set new current states to next states
      updateCurrentState(nextStates);

      // handle received spec at most once
      for (Transition tr : transitions) {
        if (tr.handle) {
          receivedSpec.handle();
          return;
        }
      }
    }
  }

  private boolean tryDefaultActions(EventSpec receivedSpec) {
    Action action = defaultLookup(receivedSpec);
    if (action == null) {
      //logger.debug("no default action found");
      return false;
    }

    //logger.debug("default action {}", action));
    switch (action) {
      case FAIL:
        logger.debug("Received unwanted event {} at state {}", receivedSpec, currentStates);
        return false;
      case HANDLE:
        receivedSpec.handle();
      default:
        return true;
    }
  }

  private void forceInternalEventTransitions(Set<State> nextStates) {
    assert nextStates.isEmpty();
    for (State state : currentStates) {
      if (state.canPerformInternalTransition()) {
        Collection<Transition> transitions = state.doInternalEventTransition();
        assert !transitions.isEmpty();
        for (Transition t : transitions) {
          nextStates.add(t.nextState);
        }
        //return;
      }
    }
  }

  void tryInternalEventTransitions() {
    //logger.debug("{}: trying internal event", currentStates);
    while (true) {
      // if some thread in the NFA expects an event, do nothing
      for (State state : currentStates) {
        if (!state.canPerformInternalTransition()) {
          //logger.debug("state {} has no internal event. returning", state);
          return;
        }
      }

      // all current states have internal event specs (trigger, inspect etc) -
      // perform them
      // if so, non-singular set currentStates implies an ambiguous test specification
      Set<State> nextStates = new HashSet<State>();
      for (State state : currentStates) {
        //logger.debug("{} performing internal transition", state);
        Collection<Transition> transitions = state.doInternalEventTransition();
        assert !transitions.isEmpty();
        for (Transition t : transitions) {
          nextStates.add(t.nextState);
        }
      }
      assert !nextStates.isEmpty();
      //logger.debug("all states have internal transitions");
      updateCurrentState(nextStates);
    }
  }

  private void updateCurrentState(Set<State> nextStates) {
    logger.debug("{}: new state is {}", currentStates, nextStates);

    // recompute active blocks
    activeBlocks.clear();

    // reset discontinued blocks
    Block block;
    for (State state : nextStates) {
      block = state.block;
      while (block != null && !activeBlocks.contains(block)) {
        activeBlocks.add(block);
        block = block.previousBlock;
      }
    }
    //logger.debug("active blocks = {}", activeBlocks);

    for (State state : currentStates) {
      if (!nextStates.contains(state) && !activeBlocks.contains(state.block)) {
        logger.debug("reseting {}, for state {}", state.block, state);
        state.block.reset();
      }
    }

    // update current state
    currentStates.clear();
    for (State state : nextStates) {
      assert !state.eclosure().isEmpty();
      currentStates.addAll(state.eclosure());
    }
  }

  /**
   *
   *    PRIVATE CLASSES
   *
   */

  private class BaseFA extends FA{
    Spec spec;

    BaseFA(Block block) {
      super(block);
      startState = new State(nextid(), block);
    }

    @Override
    void build(FA finalFA) {
      assert spec != null;
      startState.addTransition(new Transition(spec, finalFA.startState, true));
      if (spec instanceof InternalEventSpec) {
        State s = startState;
        s.internalEventSpec = (InternalEventSpec) spec;
      }
    }

    @Override
    public String toString() {
      return "BaseFA " + startState;
    }

    @Override
    void end() {}
  }

  private class KleeneFA extends FA {

    KleeneFA(Block block) {
      super(block);
    }

    @Override
    void addSpec(Spec spec) {
      // TODO: 4/27/17 merge with repeatFA addspec
      BaseFA child = new BaseFA(block);
      child.spec = spec;
      children.add(child);
    }

    @Override
    void build(FA finalFA) {
      FA endFA = children.get(children.size() - 1);

      FA next = endFA;
      FA current;
      for (int i = children.size() - 2; i >= 0; i--) { // ignore last
        current = children.get(i);
        current.build(next);
        next = current;
      }

      // set kleene start
      FA firstChild = children.get(0);
      firstChild.startState.isKleeneStart = true;
      startState = firstChild.startState;

      // set loopback transition from end
      State endState = endFA.startState;
      assert endState.isKleeneEnd;
      endState.loopTransition.add(new Transition(startState));

      // set e-transition outside of block
      startState.addTransition(new Transition(EventSpec.EPSILON, finalFA.startState));

      if (!(firstChild instanceof BaseFA)) {
        startState.parentBlocks.add(0, block);
      }
    }

    @Override
    void end() {
      FA blockEnd = new BaseFA(block);
      blockEnd.startState.setKleeneEnd();
      children.add(blockEnd);
    }

  }

  private class RepeatFA extends FA {
    final int count;
    RepeatFA(int count, Block block) {
      super(block);
      assert count >= 1;
      this.count = count;
    }

    @Override
    void addSpec(Spec spec) {
      BaseFA child = new BaseFA(block);
      child.spec = spec;
      children.add(child);
    }

    @Override
    void build(FA finalFA) {
      // build children
      FA endFA = children.get(children.size() - 1);
      FA next = endFA;
      FA current;
      for (int i = children.size() - 2; i >= 0; i--) { // ignore repeatend
        current = children.get(i);
        current.build(next);
        next = current;
      }

      // set start of repeat block
      FA firstChild = children.get(0);
      startState = firstChild.startState;
      startState.isRepeatStart = true;

      // end of repeat block
      State endState = endFA.startState;
      assert endState.isRepeatEnd;
      endState.exitTransition.add(new Transition(finalFA.startState));
      endState.loopTransition.add(new Transition(startState));

      // if repeat has a nested start state 's' belonging to another block
      // register block to be run on entry to 's'
      if (!(firstChild instanceof BaseFA)) {
        startState.parentBlocks.add(0, block);
      }
    }

    @Override
    void end() {
      FA blockEnd = new BaseFA(block);
      blockEnd.startState.setRepeatEnd();
      children.add(blockEnd);
    }
  }

  private class ConditionalFA extends FA{
    private boolean inEitherBlock = true;
    private List<FA> eitherBranch = new ArrayList<FA>();
    private List<FA> orBranch = new ArrayList<FA>();

    ConditionalFA(Block block) {
      super(block);
      startState = new State(nextid(), block);
    }

    void or() {
      if (!inEitherBlock) {
        throw new IllegalStateException("multiple calls to or for conditional");
      }
      inEitherBlock = false;
    }

    void addSpec(Spec spec) {
      // // TODO: 5/13/17 merge with repeat/kleene
      BaseFA child = new BaseFA(block);
      child.spec = spec;
      addFA(child);
    }

    void addFA(FA childFA) {
      if (inEitherBlock) {
        eitherBranch.add(childFA);
      } else {
        orBranch.add(childFA);
      }
    }

    @Override
    void build(FA finalFA) {
      buildBranch(finalFA, eitherBranch);
      buildBranch(finalFA, orBranch);
    }

    private void buildBranch(FA finalFA, List<FA> branch) {
      FA next = finalFA;
      FA current;
      for (int i = branch.size() - 1; i >= 0; i--) {
        current = branch.get(i);
        current.build(next);
        next = current;
      }
      State branchStartState = branch.get(0).startState;
      startState.addTransition(new Transition(EventSpec.EPSILON, branchStartState));
    }

    private void checkNonEmptyBranch() {
      String branch = null;
      if (eitherBranch.isEmpty()) {
        branch = "either";
      } else if (orBranch.isEmpty()) {
        branch = "or";
      }
      if (branch != null) {
        throw new IllegalStateException("empty " + branch + " branch");
      }
    }

    @Override
    void end() {
      checkNonEmptyBranch();
    }
  }

  abstract class FA {
    State startState;
    Collection<State> endStates;
    // TODO: 4/27/17 dont create new list for baseFAs
    List<FA> children = new ArrayList<FA>();
    Set<State> states = new HashSet<State>();
    final Block block;

    FA(Block block) {
      this.block = block;
    }

    abstract void build(FA finalFA);

    void addSpec(Spec spec) {
      throw new UnsupportedOperationException();
    }

    void addFA(FA childFA) {
      children.add(childFA) ;
    }

    abstract void end();
  }


  private class State {
    final int id;
    final Block block;
    Multimap<Spec, Transition> transitions = HashMultimap.<Spec, Transition>create();

    boolean isFinalState;
    boolean isRepeatStart;
    boolean isRepeatEnd;
    boolean isKleeneStart;
    boolean isKleeneEnd;
    InternalEventSpec internalEventSpec;
    List<Block> parentBlocks = new LinkedList<Block>();

    Set<State> eclosure;

    Collection<Transition> loopTransition;
    Collection<Transition> exitTransition;
    Collection<Transition> selfTransition;

    State(int number, Block block) {
      this.block = block;
      id = number;
    }

    void addTransition(Transition t) {
      transitions.put(t.spec, t);
    }

    Collection<Transition> getTransition(EventSpec receivedSpec) {
      Collection<Transition> t = new HashSet<Transition>();

      // don't transition out of completed loop on event
      if (isEndOfLoop() && !block.hasPendingEvents()) {
        return t;
      }

      // blockExpect
      if (block.handle(receivedSpec)) {
        t.add(new Transition(receivedSpec, this, true)); // handle received event
      }

      // if block doesn't handle, consider other transitions
      if (t.isEmpty()) {
        for (Map.Entry<Spec, Transition> entry : transitions.entries()) {
          Spec spec = entry.getKey();
          if (spec.match(receivedSpec)) {
            if (spec instanceof MultiEventSpec) {
              State nextState = this;
              if (((MultiEventSpec) spec).isComplete()) {
                nextState = entry.getValue().nextState;
              }
              t.add(new Transition(receivedSpec, nextState));
            } else {
              t.add(entry.getValue());
            }
          }
        }
      }

      // allow, disallow, drop transitions
      if (t.isEmpty()) {
        Transition blockTransition = handleWithBlockTransitions(receivedSpec);
        if (blockTransition != null) {
          t.add(blockTransition);
        }
      }

      if (isStartOfLoop() && !t.isEmpty()) {
        runBlockInits();
      }

      return t;
    }

    private Transition handleWithBlockTransitions(EventSpec receivedSpec) {
      logger.debug("{}: looking up {} with constraints {}", this, receivedSpec, block.status());
      if (block.getAllowedSpecs().contains(receivedSpec)) {
        return new Transition(receivedSpec, this, true);
      }
      if (block.getDroppedSpecs().contains(receivedSpec)) {
        return new Transition(receivedSpec, this);
      }
      if (block.getDisallowedSpecs().contains(receivedSpec)) {
        return new Transition(receivedSpec, errorState);
      }
      return null;
    }

    Collection<Transition> doInternalEventTransition() {
      // if start state is interaction etc (run block init before performing event)
      if (isStartOfLoop()) {
        runBlockInits();
      }

      // trigger, inspect, etc
      if (internalEventSpec != null) {
        internalEventSpec.performInternalEvent();
        return transitions.get(internalEventSpec);
      }

      if (isEndOfLoop()) {
        return getLoopEndTransition();
      }
      return null;
    }

    private boolean canPerformInternalTransition() {
      if (isEndOfLoop()) { // if block is complete, loop back transition is possible
        return !block.hasPendingEvents();
      }
      return internalEventSpec != null;
    }

    private void runBlockInits() {
      boolean canRunInit = false; // parent block is closed -> nested blocks are closed
      for (Block parent : parentBlocks) {
        if (canRunInit) {
          assert parent.canRunInit();
        }

        if (parent.canRunInit()) {
          canRunInit = true;
          runInitializeFor(parent);
        }
      }
      runInitializeFor(block);
    }

    private void runInitializeFor(Block block) {
      logger.trace("{}: running initialize() for block {}", this, block);
      block.initialize();
    }

    private Collection<Transition> getLoopEndTransition() {
      if (!block.hasPendingEvents()) {
        logger.debug("end{} count = {}", block, block.getCurrentCount());

        block.iterationComplete(); // decrement loop count (for kleene closure disable block)

        assert loopTransition != null;

        if (isRepeatEnd) {
          assert exitTransition != null;
        }

        if (isKleeneEnd || (isRepeatEnd && block.hasMoreIterations())) {
          return loopTransition;
        } else {
          // close block on exit
          block.close();

          return exitTransition;
        }
      }
      return selfTransition;
    }
    Set<State> eclosure() {
      if (eclosure != null) {
        assert !eclosure.isEmpty();
        return eclosure;
      }
      Set<State> eclose = new HashSet<State>();
      LinkedList<State> pending = new LinkedList<State>();
      pending.add(this);
      while (!pending.isEmpty()) {
        State current = pending.removeFirst();
        eclose.add(current);

        for (Map.Entry<Spec, Transition> entry : current.transitions.entries()) {
          State s = entry.getValue().nextState;
          if (entry.getKey() == EventSpec.EPSILON && !(eclose.contains(s) || pending.contains(s))) {
            pending.add(s);
          }
        }
      }
      eclosure = eclose;
      return eclosure;
    }

    void setRepeatEnd() {
      isRepeatEnd = true;
      loopTransition = new HashSet<Transition>();
      exitTransition = new HashSet<Transition>();
      selfTransition = new HashSet<Transition>();
      selfTransition.add(new Transition(this));
    }

    void setKleeneEnd() {
      isKleeneEnd = true;
      loopTransition = new HashSet<Transition>();
      selfTransition = new HashSet<Transition>();
      selfTransition.add(new Transition(this));
    }

    private boolean isEndOfLoop() {
      return isRepeatEnd || isKleeneEnd;
    }

    private boolean isStartOfLoop() {
      return isRepeatStart || isKleeneStart;
    }

    @Override
    public String toString() {
      //return id.toString();
      return Integer.toString(id);
    }

    String show() {
      StringBuilder sb = new StringBuilder(id);
      sb.append(transitions.values());
      return sb.toString();
    }
  }

  private class Transition {
    final Spec spec;
    final State nextState;
    boolean handle;

    Transition(Spec spec, State nextState) {
      this.spec = spec;
      this.nextState = nextState;
    }

    Transition(State nextState) {
      this.nextState = nextState;
      this.spec = null;
    }

    Transition(Spec spec, State nextState, boolean handle) {
      this(spec, nextState);
      this.handle = handle;
    }

    @Override
    public String toString() {
      return spec + "->" + nextState;
    }

  }
}


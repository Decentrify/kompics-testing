/*
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

import com.google.common.base.Supplier;
import org.slf4j.Logger;

import se.sics.kompics.Component;
import se.sics.kompics.ComponentCore;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Fault;
import se.sics.kompics.JavaComponent;
import se.sics.kompics.PortType;
import se.sics.kompics.Port;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.Start;

import static se.sics.kompics.testing.MODE.*;
import static se.sics.kompics.testing.Block.STAR;

/**
 * Validate a test specification, construct and run an NFA from it.
 */
class Ctrl<T extends ComponentDefinition> {
    // LOG.
    private static final Logger logger = TestContext.logger;

    // ComponentDefinition of the CUT.
    private final T definitionUnderTest;

    /**
     * Queue of observed event symbols {@link se.sics.kompics.testing.EventSymbol}
     * */
    private final EventQueue eventQueue;

    // Track if we have previously run the testcase.
    private boolean STARTED = false;

    /** The {@link Proxy} for this testcase  */
    private final ComponentCore proxyComponent;

    // Component dependencies for this test case. This consists of
    // the CUT and all of its peers.
    private Collection<Component> dependencies = new HashSet<Component>();

    /**
     *  Collect the answerRequest statements in a
     *  {@link #answerRequests()}.
     */
    private ArrayList<AnswerRequestLabel> requestSequence;

    // Track the Futures we have seen so far in the test specification.
    private Set<Future<? extends KompicsEvent, ? extends KompicsEvent>> futures =
      new HashSet<Future<? extends KompicsEvent, ? extends KompicsEvent>>();

    // Set to true if events should be forwarded
    // immediately in the current unordered statement.
    // otherwise events will be forwarded together
    // once all the expected events have been matched (Batch mode).
    private boolean UNORDERED_MODE_FORWARD_POLICY;

    /**
     *  Contains labels for statements in a {@link TestContext#unordered}
     *  statement.
     */
    private List<SingleLabel> unorderedLabels;

    // Contains registered comparators for determining event equivalence
    // in a test case.
    private ComparatorMap comparators = new ComparatorMap();

    // The block to which we are currently adding statements
    // and possibly nested blocks.
    // Initially set to the Main Repeat Block within which all
    // statements in the test case are run (and blocks are nested).
    private Block currentBlock = new Block(null, 1);

    // The Non-Deterministic Finite Automaton with which we run the
    // entire test case.
    private NFA table = new NFA(currentBlock);

    // The mode in which we are currently adding statements from the
    // test specification.
    private MODE currentMode = HEADER;

    // Save nested blocks in this stack for reactivation later.
    private Stack<MODE> previousMode = new Stack<MODE>();

    /**
     * Track the number of currently unbalanced statements - That is
     * statements that require but are without a matching call to the
     * {@link TestContext#end()} statement. (e.g repeat, either)
     */
    private int balancedEnd = 0;

    Ctrl(Proxy<T> proxy, T definitionUnderTest) {
        // Store the event symbol queue.
        eventQueue = proxy.getEventQueue();

        // Store the CUT.
        proxyComponent = proxy.getComponentCore();

        // Initialize the CUT component definition.
        this.definitionUnderTest = definitionUnderTest;

        // Begin the test specification in the initial-header mode.
        previousMode.push(HEADER);
    }

    /**
     *  Add a dependency component to the test case.
     *  Dependencies are sent the start event only after
     *  {@link #proxyComponent} has been started and before the
     *  NFA is executed.
    */
    void addDependency(Component c) {
        // Verify that we are in the initial-header.
        checkInInitialHeader();

        // Add the component to the dependency list.
        dependencies.add(c);
    }

    // Black-list events matched by this label within the current block.
    void blacklist(EventLabel label) {
        // Verify allowed modes for this statement.
        assertMode(HEADER);

        // Add label to the current block's blacklist.
        currentBlock.blacklist(label);
    }

    // White-list events matched by this label within the current block.
    void whitelist(EventLabel label) {
        // Verify allowed modes for this statement.
        assertMode(HEADER);

        // Add label to the current block's whitelist.
        currentBlock.whitelist(label);
    }

    // Do not forward events matched by this label.
    void drop(EventLabel label) {
        // Verify allowed modes for this statement.
        assertMode(HEADER);

        // Add label to the current block's list of dropped events.
        currentBlock.drop(label);
    }

    // Add a state transition with the provided label to the NFA.
    void expect(EventLabel label) {
        // Since expect statements can be used in
        // different contexts, we add the transition based
        // on the current mode.
        switch (currentMode) {
            case BODY:
            case CONDITIONAL:
                // Add directly to the NFA.
                table.addLabel(label);
                break;
            case UNORDERED:
                // Add to the enclosing unordered automaton.
                unorderedLabels.add(label);
                break;
            default:
                // Invalid mode.
                fail(BODY, CONDITIONAL, UNORDERED);
        }
    }

    // Add a state transition with the provided label to the current block.
    void blockExpect(EventLabel label) {
        // Verify allowed modes for this statement.
        assertMode(HEADER);

        // Add label to current block.
        currentBlock.expect(label);
    }

    // Switch the current mode to UNORDERED and create a new
    // unordered label.
    void setUnorderedMode() {
        // Create the unordered label that forwards matched
        // events immediately by default.
        setUnorderedMode(true);
    }

    // Switch the current mode to UNORDERED and create a new
    // unordered label.
    void setUnorderedMode(boolean immediateResponse) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Set the forward policy for this unordered statement.
        UNORDERED_MODE_FORWARD_POLICY = immediateResponse;

        // Enter a new mode and save the test case's previous mode.
        pushMode(UNORDERED);

        // Initialize our collection for internal statements within this mode.
        unorderedLabels = new ArrayList<SingleLabel>();
    }

    // Close the current unordered statement.
    private void endUnorderedMode() {
        // We must have at least one statement within the unordered statement.
        if (unorderedLabels.isEmpty())
            throw new IllegalStateException("No events were specified in unordered mode");

        // Reactivate the previous mode.
        popMode();

        // Create a transition label for the unordered statement.
        UnorderedLabel label = new UnorderedLabel(unorderedLabels,
                                                  UNORDERED_MODE_FORWARD_POLICY);

        // Add the label to the NFA.
        table.addLabel(label);
    }

    // Create an AnswerRequestLabel with a mapper function.
    <RQ extends KompicsEvent, RS extends KompicsEvent>
    void answerRequest(Class<RQ> requestType,
                       Port<? extends PortType> requestPort,
                       Function<RQ, RS> mapper,
                       Port<? extends PortType> responsePort) {

        // Verify allowed modes for this statement.
        assertMode(UNORDERED, ANSWER_REQUEST, BODY, CONDITIONAL);

        // Set to true if we should trigger responses immediately.
        boolean triggerImmediately;

        // To set the trigger policy for this statement, check
        // if we are in unordered mode.
        if (currentMode == UNORDERED) {
            // If yes, then our trigger policy is dependent
            // on that of the unordered mode
            triggerImmediately = UNORDERED_MODE_FORWARD_POLICY;
        } else {
            // Otherwise, this answerRequest statement is either a stand-alone
            // statement or part of a sequence of answerRequest statements.
            // Stand-alone statement always trigger immediately.
            // Statements in a sequence always trigger in batch.

            // Set trigger policy to the opposite.
            triggerImmediately = currentMode != ANSWER_REQUEST;
        }

        // Create the transition label for this statement and
        // add it to the NFA.
        AnswerRequestLabel ARLabel = new AnswerRequestLabel(requestType,
                                                            requestPort,
                                                            mapper,
                                                            responsePort,
                                                            triggerImmediately,
                                                            proxyComponent);

        // Are we in UNORDERED mode?
        if (currentMode == UNORDERED) {
            // If yes, then the label as a child to the unordered statement.
            unorderedLabels.add(ARLabel);
        } else {
            // Otherwise, Batch mode implies that this statement is part
            // of a sequence of answerRequest statements.
            if (!triggerImmediately) {
                assert requestSequence != null;

                // If so, add the label to the sequence and set it's reference.
                requestSequence.add(ARLabel);
                ARLabel.setRequestSequence(requestSequence);
            }

            // Finally, add this label to the NFA as its own state.
            table.addLabel(ARLabel);
        }
    }

    // Create an AnswerRequestLabel with a future.
    <RQ extends KompicsEvent>
    void answerRequest(Class<RQ> requestType,
                       Port<? extends PortType> requestPort,
                       Future<RQ, ? extends KompicsEvent> future) {

        // Verify allowed modes for this statement.
        assertMode(UNORDERED, BODY, CONDITIONAL);

        // Has this future been used previously?
        if (futures.contains(future))
            throw new IllegalArgumentException("Future can only be used once");

        // If no, add it.
        futures.add(future);

        // Create the transition label for this statement and
        // add it to the NFA.
        AnswerRequestLabel ARLabel = new AnswerRequestLabel(requestType,
                                                            requestPort,
                                                            future,
                                                            proxyComponent);

        // If we are in UNORDERED mode, then add the label as a child
        // to the enclosing unordered statement otherwise
        // add to the NFA as its own state.
        if (currentMode == UNORDERED)
            unorderedLabels.add(ARLabel);
        else
            table.addLabel(ARLabel);
    }

    // Begin a sequence of AnswerRequest statements.
    void answerRequests() {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Enter a new mode and save the test case's previous mode.
        pushMode(ANSWER_REQUEST);

        // Save the contained AnswerRequest statements in order.
        requestSequence = new ArrayList<AnswerRequestLabel>();
    }

    // Terminate a sequence of AnswerRequest statements.
    private void endAnswerRequestMode() {
        // Reactivate the previous mode.
        popMode();

        // Do we have a sequence?
        if (requestSequence.isEmpty())
            throw new IllegalStateException(
                "No answerRequest statements were provided in Mode[" + ANSWER_REQUEST + "]");

        // Mark the last statement in this sequence.
        requestSequence.get(requestSequence.size() - 1).setLastRequest(true);

        // Reset sequence.
        requestSequence = null;
    }

    // Add a TRIGGER statement to the NFA.
    void trigger(KompicsEvent event, Port<? extends PortType> port) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Create a TRIGGER transition label.
        InternalLabel label = new InternalLabel(event, port);

        // Add it to NFA.
        table.addLabel(label);
    }

    // Add a TRIGGER statement to the NFA.
    void trigger(Supplier<? extends KompicsEvent> supplier, Port<? extends PortType> port) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Create a TRIGGER transition label.
        InternalLabel label = new InternalLabel(supplier, port);

        // Add it to NFA.
        table.addLabel(label);
    }
    // Add a TRIGGER internal transition to the NFA.
    // The triggered event is retrieved by calling future.
    <E extends KompicsEvent, R extends KompicsEvent, P extends PortType>
    void trigger(Future<E, R> future, Port<P> responsePort) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Has this future been set previously?
        if (!futures.contains(future))
            throw new IllegalArgumentException(
                "Future must be used in a previous statement before calling trigger.");

        // If yes, create a TRIGGER transition label.
        InternalLabel label = new InternalLabel(future, responsePort);

        // Add it to NFA.
        table.addLabel(label);
    }

    // Create a new Conditional to start adding statements in its either branch.
    void either() {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Enter a new mode and save the test case's previous mode.
        pushMode(CONDITIONAL);

        // Create conditional.
        table.either(currentBlock);
    }

    // Start adding statements in the current Conditional to the $or branch.
    void or() {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Make the switch.
        table.or();
    }

    // Close the current Conditional statement.
    private void endConditional() {
        // Reactivate previous mode.
        popMode();

        // Close the conditional inside the NFA.
        table.end();
    }

    // Create a new Repeat block to which subsequent statements are added.
    // Statements within the created block will be executed count times on entry.
    void repeat(int count) {
        // count must be positive.
        if (count <= 0) {
            throw new IllegalArgumentException("only positive value allowed for block");
        }

        // Create the Repeat block and nest it within the current block.
        Block block = new Block(currentBlock, count);

        // Activate the block by setting it as the currentBlock.
        enterNewBlock(count, block);
    }

    // Create a new Kleene block to which subsequent statements are added.
    void repeat() {
        // Create the Kleene block and nest it within the current block.
        Block block = new Block(currentBlock, STAR);

        // Activate the block by setting it as the currentBlock.
        enterNewBlock(STAR, block);
    }

    // Create a new Repeat block to which subsequent statements are added.
    // Statements within the created block will be executed count times on entry.
    // Set init as the EntryFunction to this block.
    void repeat(int count, EntryFunction init) {
        // count must be positive.
        if (count <= 0) {
            throw new IllegalArgumentException("only positive value allowed for block");
        }

        // Create the Repeat block and nest it within the current block.
        Block block = new Block(currentBlock, count, init);

        // Activate the block by setting it as the currentBlock.
        enterNewBlock(count, block);
    }

    // Create a new Kleene block to which subsequent statements are added.
    // Set init as the EntryFunction to this block.
    void repeat(EntryFunction init) {
        // Create the Kleene block and nest it within the current block.
        Block block = new Block(currentBlock, STAR, init);

        // Activate the block by setting it as the currentBlock.
        enterNewBlock(STAR, block);
    }

    // Set block as the current block to which subsequent statements are
    // added.
    private void enterNewBlock(int count, Block block) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Set current mode to the HEADER of block.
        pushMode(HEADER);

        // Make the switch.
        currentBlock = block;

        // Create an automaton in the NFA for this block.
        table.addRepeat(count, block);
    }


    // Close the current Repeat/Kleene block.
    private void endRepeat() {
        // Reactivate the previous block.
        currentBlock = currentBlock.previousBlock;

        // Reactivate the previous mode.
        popMode();

        // Check if the statements are still balanced.
        if (balancedEnd < 0) {
            throw new IllegalStateException("No matching block for end operation");
        }

        // Close the block inside the NFA.
        table.end();
    }

    // Switch from HEADER to BODY mode within the test specification
    void body() {
        // Verify allowed mode.
        assertMode(HEADER);

        // Make the switch.
        setMode(BODY);
    }

    // End the most recent unbalanced statement.
    void end() {
        // What action we perform depend on what the statement is.
        // This is deducible from our current MODE.
        switch (currentMode) {
            case UNORDERED:
                // End an unordered statement.
                endUnorderedMode();
                break;
            case CONDITIONAL:
                // End a conditional statement.
                endConditional();
                break;
            case BODY:
                // End a Repeat or Kleene block.
                endRepeat();
                break;
            case ANSWER_REQUEST:
                // End an answerRequests statement.
                endAnswerRequestMode();
                break;
            default:
                throw new IllegalStateException("END not allowed in mode " + currentMode);
        }
    }

    // Adds a transition with the provided FaultLabel to the NFA.
    void expectFault(FaultLabel label) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Add to the NFA.
        table.addLabel(label);
    }

    // Add an INSPECT internal transition to the NFA.
    void inspect(Predicate<T> inspectPredicate) {
        // Verify allowed modes for this statement.
        assertMode(BODY, CONDITIONAL);

        // Create internal label for the inspect statement.
        InternalLabel label = new InternalLabel(definitionUnderTest,
                                                inspectPredicate);

        // Add to the NFA.
        table.addLabel(label);
    }

    // Set a Comparator for determining the equivalence of
    // events belonging to class eventType.
    <E extends KompicsEvent>
    void setComparator(Class<E> eventType,
                       Comparator<E> comparator) {
        // Verify we are in initial header.
        checkInInitialHeader();

        // Register comparator for eventType.
        comparators.put(eventType, comparator);
    }

    // Register a default action to be taken for unmatched events
    // belong to class eventType.
    <E extends KompicsEvent>
    void setDefaultAction(Class<E> eventType,
                          Function<E, Action> function) {
        // Verify we are in initial header.
        checkInInitialHeader();

        // Register the function.
        table.registerDefaultAction(eventType, function);
    }

    // Set the maximum we should wait for events to be observed
    // in the test case environment.
    void setTimeout(long timeoutMS) {
        // Verify we are in initial header.
        checkInInitialHeader();

        // Timeout value must be non-negative.
        if (timeoutMS < 0)
            throw new IllegalStateException("Negative timeout");

        // Set the event queue's timeout value.
        eventQueue.setTimeout(timeoutMS);
    }

    // Throw an exception if we are not currently in the  initial header.
    void checkInInitialHeader() {
        // First we must be in the HEADER mode.
        assertMode(HEADER);

        // Next this must also be the HEADER mode of the first (Main) Repeat block.
        if (currentBlock.previousBlock != null)
            throw new IllegalStateException("Operation only supported in initial header");
    }

    // Create an transition label that matches event.
    @SuppressWarnings("unchecked")
    <P extends  PortType, E extends KompicsEvent>
    EventLabel createEventLabel(KompicsEvent event,
                                Port<P> port,
                                Direction direction) {
        // Set a comparator for comparing events of this
        // type if one was registered.
        Comparator<E> c = (Comparator<E>) comparators.get(event.getClass());
        return new EventLabel((E) event, port, direction, c);
    }

    // Create a Predicate Label that matches all events belonging to class eventType.
    <E extends KompicsEvent>
    EventLabel createPredicateLabel(Class<E> eventType,
                                    Port<? extends PortType> port,
                                    Direction direction) {
        Predicate<E> predicate = new Predicate<E>() {
            @Override
            public boolean apply(E e) {
                return true;
            }
        };
        return createPredicateLabel(eventType, predicate, port, direction);
    }

    // Create a Predicate Label to match events using predicate.
    <E extends KompicsEvent>
    EventLabel createPredicateLabel(Class<E> eventType,
                                    Predicate<E> predicate,
                                    Port<? extends PortType> port,
                                    Direction direction) {
        return new EventLabel(eventType, predicate, port, direction);
    }

    // Run this test case.
    boolean execute() {
        // Have we run this test case previously?
        if (STARTED)
            return false;

        // Verify that no all blocks are balanced.
        if (balancedEnd != 0)
            throw new IllegalStateException("Unbalanced block");

        // Flag this test case has been run.
        STARTED = true;

        // Send start event to all specified components in the test case.
        initializeDependencies();

        // Return true if the test case passed.
        return run();
    }

    // Run the test case and return true if it passed.
    private boolean run() {
        // Construct our NFA.
        table.construct();

        // We always try to
        while (true) {
            // See if we are not expecting any events to occur and if so,
            // perform any internal transitions from our current states.
            table.tryInternalEventTransitions();

            // If we already ended up in an error state,
            // then return immediately.
            if (table.inErrorState())
                return false;

            // Wait for an event to be observed.
            EventSymbol eventSymbol = eventQueue.poll();

            // If no more events were observed and we are in the
            // final state, then the test case passes.
            if (eventSymbol == null && table.isInFinalState())
                return true;

            // Otherwise, we either have an event that needs to be matched or
            // we might need to perform some internal transition(s).
            boolean successful = table.doTransition(eventSymbol);

            // If we were not successful, then either
            // 1) The event was null.
            // 2) The event could not be matched successfully.
            // 3) Some internal transition has caused us to fail.
            // in 1) we may have ended up in the final state
            // while in 2) and 3) we must always fail the test case.
            if (!successful)
                return testCasePassed(eventSymbol);
        }
    }

    // Return true if the test cases has passed given the observed event.
    private boolean testCasePassed(EventSymbol eventSymbol) {
        // If we ended up in the final state and no event was observed
        // then the test case passed.
        if (eventSymbol == null && table.isInFinalState()) {
            // Wait for any pending events to be handled by the CUT
            checkWorkCount();
            return true;
        }

        // Otherwise we have observed an event unmatchable event and
        // must fail the test case.

        // If this event is an exception caught by the framework,
        // then log the exception to stdErr before failing.
        logException(eventSymbol);

        // The test failed.
        return false;
    }

    // Print an error message to stdErr if the provided event is
    // a fault event.
    private void logException(EventSymbol eventSymbol) {
        if (eventSymbol != null
            && eventSymbol.getEvent() instanceof Fault
            && eventSymbol.getPort() == definitionUnderTest.getControlPort())
            logger.error("Uncaught Exception [{}]", eventSymbol.getEvent());
    }

    // Wait for any pending events to be handled by the CUT
    private void checkWorkCount() {
        JavaComponent cut = (JavaComponent) proxyComponent;
        while (cut.workCount.get() > 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // Push mode onto stack and set as the current mode.
    private void pushMode(MODE mode) {
        previousMode.push(currentMode);
        setMode(mode);
        balancedEnd++;
    }

    // Restore the previous mode.
    private void popMode() {
        currentMode = previousMode.pop();
        balancedEnd--;
        if (balancedEnd < 0)
            throw new IllegalStateException("Unbalanced block");
    }

    // Set the current mode.
    private void setMode(MODE mode) {
        currentMode = mode;
    }

    // Throw an error if the current mode isn't any
    // of the provided modes.
    private void assertMode(MODE... allowedModes) {
        for (MODE mode : allowedModes)
            if (currentMode == mode)
                return;

        fail(allowedModes);
    }

    // Throw an error with message saying that the current mode
    // isn't any of the provided modes.
    private void fail(MODE... expectedModes) {
        throw new IllegalStateException(
            String.format("Current Modes was [%s]. Allowed Modes for this statement is %s",
                          currentMode,
                          Arrays.toString(expectedModes)));
    }

    // Sends start events to all components participating in the test case.
    private void initializeDependencies() {
        logger.trace("Sending Start to {} participant component(s)", dependencies.size());
        for (Component child : dependencies) {
            child.getControl().doTrigger(Start.event, 0, proxyComponent);
        }
    }

    // Wrapper class to look up comparator for given event class.
    private class ComparatorMap {
        Map<Class<? extends KompicsEvent>, Comparator<? extends KompicsEvent>> comparators =
            new HashMap<Class<? extends KompicsEvent>, Comparator<? extends KompicsEvent>>();

        @SuppressWarnings("unchecked")
        public <E extends KompicsEvent> Comparator<E> get(Class<E> eventType) {
            return (Comparator<E>) comparators.get(eventType);
        }

        public <E extends KompicsEvent> void put(Class<E> eventType, Comparator<E> comparator) {
            comparators.put(eventType, comparator);
        }
    }
}

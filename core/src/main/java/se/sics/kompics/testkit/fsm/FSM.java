package se.sics.kompics.testkit.fsm;

import se.sics.kompics.ComponentCore;
import se.sics.kompics.Kompics;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.testkit.Proxy;

import java.util.LinkedList;
import java.util.Queue;

public class FSM {
  private final EventQueue eventQueue;
  private final Queue<State> stateQueue = new LinkedList<>();
  private final Proxy proxy;
  private final ComponentCore proxyComponent;
  private boolean start = false;

  public FSM(Proxy proxy) {
    this.proxy = proxy;
    this.eventQueue = proxy.getEventQueue();
    this.proxyComponent =  proxy.getComponentCore();
    stateQueue.offer(new StartState(proxyComponent));
  }

  public void addState(State state) {
    stateQueue.offer(state);
  }

  public void start() {
    if (!start) {
      start = true;
      addState(new FinalState());
      run();
    }
  }

  private void run() {
    State currentState = stateQueue.peek();
    while (!stateQueue.isEmpty()) {
      Kompics.logger.info("Current State = {}", currentState.getClass().getSimpleName());
      boolean completed = currentState.run();
      Kompics.logger.info("returned {}", completed);
      if (completed) {
        stateQueue.poll();
        currentState = stateQueue.peek();
      } else {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }

  KompicsEvent peekEventQueue() {
    return eventQueue.peek();
  }

  KompicsEvent pollEventQueue() {
    return eventQueue.poll();
  }

  void waitNewEvent() {
    synchronized (eventQueue) {
      try {
        eventQueue.wait();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}

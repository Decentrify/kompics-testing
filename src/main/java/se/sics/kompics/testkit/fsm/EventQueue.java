package se.sics.kompics.testkit.fsm;


import se.sics.kompics.Kompics;

import java.util.concurrent.ConcurrentLinkedQueue;

public class EventQueue {

  private final ConcurrentLinkedQueue<EventSpec> q = new ConcurrentLinkedQueue<>();

  public synchronized void offer(EventSpec event) {
    q.offer(event);
    this.notifyAll();
  }

  public synchronized EventSpec poll() {
    while (q.peek() == null) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    return q.poll();
  }

  public synchronized EventSpec peek() {
    return q.peek();
  }
}

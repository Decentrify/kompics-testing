package se.sics.kompics.testkit;

import se.sics.kompics.*;
import se.sics.kompics.scheduler.ThreadPoolScheduler;
import se.sics.kompics.testkit.fsm.FSM;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;


public class TestCase {
  private final Proxy proxy;
  private final ComponentCore proxyComponent;
  private final PortConfig portConfig;
  private ComponentDefinition cut;
  private FSM fsm;
  private Scheduler scheduler;
  private boolean checked;

  private Collection<Component> children = new HashSet<>();

  <T extends ComponentDefinition> TestCase(
          Class<T> cutClass, Init<T> initEvent) {
    proxy = new Proxy(cutClass, initEvent);
    proxyComponent = proxy.getComponentCore();
    portConfig = new PortConfig(proxy);
    init();
  }

  private void init() {
    fsm = new FSM(proxy, this);

    // default scheduler
    scheduler = new ThreadPoolScheduler(1);
    Kompics.setScheduler(scheduler);

    // // TODO: 2/20/17 set worker id
    proxyComponent.getControl().doTrigger(Start.event, 0, proxyComponent);
    assert proxyComponent.state() == Component.State.ACTIVE;

    proxy.createComponentUnderTest();
    cut = proxy.getCut();
    children.add(cut.getComponentCore());
  }

  public Collection<Component> getChildren() { return children; }

  public Component getComponentUnderTest() {
    return cut.getComponentCore();
  }

  // // TODO: 2/20/17 refactor create
  public <T extends ComponentDefinition> Component create(
          Class<T> cutClass, Init<T> initEvent) {
    Component c = proxy.createNewSetupComponent(cutClass, initEvent);
    children.add(c);
    return c;
  }

  // // TODO: 2/8/17 create with init, config
  public <T extends ComponentDefinition> Component create(
          Class<T> cutClass, Init.None initEvent) {
    Component c = proxy.createNewSetupComponent(cutClass, initEvent);
    children.add(c);
    return c;
  }


  // // TODO: 2/8/17 connect with channel, channelSelector
  public <P extends PortType> TestCase connect( Negative<P> negative, Positive<P> positive) {
    return connect(positive, negative);
  }

  public <P extends PortType> TestCase connect(Positive<P> positive, Negative<P> negative) {
    boolean cutOwnsPositive = positive.getPair().getOwner() == cut.getComponentCore();
    boolean cutOwnsNegative = negative.getPair().getOwner() == cut.getComponentCore();

    // non monitoring ports => connect normally
    if (!(cutOwnsPositive || cutOwnsNegative)) {
      Channel.TWO_WAY.connect((PortCore<P>) positive, (PortCore<P>) negative);
      return this;
    }

    PortCore<P> proxyPort = (PortCore<P>) (cutOwnsPositive? positive : negative);
    PortCore<P> otherPort = (PortCore<P>) (cutOwnsPositive? negative : positive);

    addConnectedPort(proxyPort == positive, proxyPort, otherPort);
    if (cutOwnsPositive && cutOwnsNegative) {
      addConnectedPort(otherPort == positive, otherPort, proxyPort);
    }

    return this;
  }

  private <P extends PortType> void addConnectedPort(
          boolean isPositive, PortCore<P> port, PortCore<P> other) {
    PortStructure<P> portStruct = portConfig.getOrCreate(port, isPositive);
    portStruct.addConnectedPort(other, Channel.TWO_WAY);
  }



  public <P extends  PortType> TestCase expect(
          KompicsEvent event, Port<P> port, TestKit.Direction direction) {

    //EventSpec eventSpec = new EventSpec(event, port, direction);
    configurePort(event, port, direction);
    //fsm.addStateToFSM(new ExpectState(eventSpec));
    //fsm.addExpect(new ExpectState(eventSpec));
    fsm.expectMessage(event, port, direction);
    return this;
  }

  public <P extends  PortType> void configurePort(
          KompicsEvent event, Port<P> port, TestKit.Direction direction) {

    if (port.getOwner() != proxyComponent) {
      // // TODO: 2/8/17 support inside ports as well
      throw new UnsupportedOperationException("Watching messages are supported only for the component being tested");
    }
    PortStructure<P> portStruct = portConfig.get(port);

    if (portStruct == null) {
      if (direction == TestKit.Direction.INCOMING) {
        throw new IllegalStateException("Can not watch incoming message on an unconnected port");
      } else {
        portStruct = portConfig.create(port);
      }
    }

    if (direction == TestKit.Direction.OUTGOING) {
      // register outgoing handler
      portStruct.addOutgoingHandler(event);
    } else if (direction == TestKit.Direction.INCOMING){
      // register incoming handler
      portStruct.addIncomingHandler(event);
    }
  }

  public <P extends PortType> TestCase trigger(
          KompicsEvent event, Port<P> port) {
    if (port.getOwner() == cut.getComponentCore()) {
      throw new IllegalStateException("Triggers are not allowed on component being tested");
    }
    fsm.addTrigger(event, port);
    return this;
  }

  public TestCase repeat(int times) {
    fsm.repeat(times);
    return this;
  }

  public TestCase end() {
    fsm.endRepeat();
    return this;
  }

  public TestCase body() {
    fsm.body();
    return this;
  }

  public <P extends  PortType> TestCase disallow(
            KompicsEvent event, Port<P> port, TestKit.Direction direction) {
    configurePort(event, port, direction);
    fsm.addDisallowedEvent(event, port, direction);
    return this;
  }

  public <P extends  PortType> TestCase allow(
            KompicsEvent event, Port<P> port, TestKit.Direction direction) {
    configurePort(event, port, direction);
    fsm.addAllowedEvent(event, port, direction);
    return this;
  }

  public <P extends  PortType> TestCase conditionalDrop(
            KompicsEvent event, Port<P> port, TestKit.Direction direction) {
    configurePort(event, port, direction);
    fsm.addDroppedEvent(event, port, direction);
    return this;
  }

  public <E extends KompicsEvent> TestCase addComparator(Class<E> eventType, Comparator<E> comparator) {
    fsm.addComparator(eventType, comparator);
    return this;
  }

  public int getFinalState() {
    return fsm.getFinalState();
  }

  public int check() {
    if (checked) {
      throw new IllegalStateException("test has previously been run");
    } else {
      checked = true;
      int errorCode = fsm.start();
      scheduler.shutdown();
      return errorCode;
    }
  }
}

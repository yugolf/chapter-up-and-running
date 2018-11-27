package com.goticks;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static akka.pattern.PatternsCS.ask;
import static akka.pattern.PatternsCS.pipe;

public class BoxOffice extends AbstractActor {
  private LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  // propsの定義
  static public Props props(Long timeout) {
    return Props.create(BoxOffice.class, () -> new BoxOffice(timeout));
  }

  private Long timeout;

  // コンストラクタ
  BoxOffice(Long timeout) {
    this.timeout = timeout;
  }

  // メッセージプロトコルの定義
  // ------------------------------------------>
  static public class CreateEvent {
    public final String name;
    public final int tickets;

    public CreateEvent(String name, int tickets) {
      this.name = name;
      this.tickets = tickets;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class GetEvent {
    public final String name;

    public GetEvent(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class GetEvents {
  }

  static public class GetTickets {
    public final String event;
    public final int tickets;

    public GetTickets(String event, int tickets) {
      this.event = event;
      this.tickets = tickets;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class CancelEvent {
    public final String name;

    public CancelEvent(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class Event {
    public final String name;
    public final int tickets;

    public Event(String name, int tickets) {
      this.name = name;
      this.tickets = tickets;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class Events {
    public final List<Event> events;

    public Events(List<Event> events) {
      this.events = events;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public abstract class EventResponse {
  }

  static public class EventCreated extends EventResponse {
    public final Event event;

    public EventCreated(Event event) {
      this.event = event;
    }

    @Override
    public String toString() {
      return ReflectionToStringBuilder.toString(this);
    }
  }

  static public class EventExists extends EventResponse {
  }

  private ActorRef createTicketSeller(String name) {
    return getContext().actorOf(TicketSeller.props(name), name);
  }
  // <------------------------------------------


  private void create(String name, int tickets) {
    ActorRef eventTickets = createTicketSeller(name);
    List<TicketSeller.Ticket> newTickets = IntStream.rangeClosed(1, tickets)
        .mapToObj(ticketId -> (new TicketSeller.Ticket(ticketId))).collect(Collectors.toList());

    eventTickets.tell(new TicketSeller.Add(newTickets), getSelf());
    getContext().sender().tell(new EventCreated(new Event(name, tickets)), getSelf());
  }

  private void notFound(String event) {
    getContext().sender().tell(new TicketSeller.Tickets(event), getSelf());
  }

  private void buy(int tickets, ActorRef child) {
    child.forward(new TicketSeller.Buy(tickets), getContext());
  }

  private CompletionStage<Events> getEvents() {
    List<CompletableFuture<Optional<Event>>> children = new ArrayList<>();
    for (ActorRef child : getContext().getChildren()) {
      children.add(ask(getSelf(), new GetEvent(child.path().name()), timeout)
          .thenApply(event -> (Optional<Event>) event).toCompletableFuture());
    }

    return CompletableFuture
        .allOf(children.toArray(new CompletableFuture[children.size()]))
        .thenApply(ignored -> {
          List<Event> events = children.stream()
              .map(CompletableFuture::join)
              .map(optionalEvent -> optionalEvent.get())
              .collect(Collectors.toList());
          return new Events(events);
        });
  }

  @Override
  public Receive createReceive() {

    return receiveBuilder()
        .match(CreateEvent.class, createEvent -> {
          log.debug("Received CreateEvent message: {}", createEvent);
          Optional<ActorRef> child = getContext().findChild(createEvent.name);
          if (child.isPresent()) {
            getContext().sender().tell(new EventExists(), self());
          } else {
            create(createEvent.name, createEvent.tickets);
          }
        }).match(GetTickets.class, getTickets -> {
          log.debug("Received GetTickets message: {}", getTickets);
          Optional<ActorRef> child = getContext().findChild(getTickets.event);
          if (child.isPresent()) {
            buy(getTickets.tickets, child.get());
          } else {
            notFound(getTickets.event);
          }
        }).match(GetEvent.class, getEvent -> {
          log.debug("Received GetEvent message: {}", getEvent);
          Optional<ActorRef> child = getContext().findChild(getEvent.name);
          if (child.isPresent()) {
            child.get().forward(new TicketSeller.GetEvent(), getContext());
          } else {
            getContext().sender().tell(Optional.empty(), getSelf());
          }
        }).match(GetEvents.class, getEvents -> {
          log.debug("Received GetEvents message: {}", getEvents);
          pipe(getEvents(), getContext().dispatcher()).to(sender());
        }).match(CancelEvent.class, cancelEvent -> {
          log.debug("Received CancelEvent message: {}", cancelEvent);
          Optional<ActorRef> child = getContext().findChild(cancelEvent.name);
          if (child.isPresent()) {
            child.get().forward(new TicketSeller.Cancel(), getContext());
          } else {
            getContext().sender().tell(Optional.empty(), getSelf());
          }
        }).build();
  }
}

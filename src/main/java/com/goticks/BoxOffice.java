package com.goticks;

import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.actor.*;
import akka.util.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import scala.Option;

import static akka.pattern.PatternsCS.ask;
import static akka.pattern.PatternsCS.pipe;

//import static scala.compat.java8.JFunction.*;

public class BoxOffice extends AbstractActor {
  private LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  // propsの定義
  static public Props props(Long timeout) {
    return Props.create(BoxOffice.class, () -> new BoxOffice(timeout));
  }

  public String name = "BoxOffice";
  private Long timeout;

  // コンストラクタ
  BoxOffice(Long timeout) {
    this.timeout = timeout;
  }

  // メッセージプロトコルの定義
  static public class CreateEvent {
    public final String name;
    public final int tickets;

    public CreateEvent(String name, int tickets) {
      this.name = name;
      this.tickets = tickets;
    }
  }

  static public class GetEvent {
    public final String name;

    public GetEvent(String name) {
      this.name = name;
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
  }

  static public class CancelEvent {
    public final String name;

    public CancelEvent(String name) {
      this.name = name;
    }
  }

  static public class Event {
    public final String name;
    public final int tickets;

    public Event(String name, int tickets) {
      this.name = name;
      this.tickets = tickets;
    }
  }

  static public class Events {
    public final List<Event> events;

    public Events(List<Event> events) {
      this.events = events;
    }
  }

  static public abstract class EventResponse {
  }

  static public class EventCreated extends EventResponse {
    public final Event event;

    public EventCreated(Event event) {
      this.event = event;
    }
  }

  static public class EventExists extends EventResponse {
  }

  private ActorRef createTicketSeller(String name) {
    return getContext().actorOf(TicketSeller.props(name), name);
  }


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

  private CompletableFuture<Events> getEvents() {

    List<CompletableFuture<Optional<Event>>> children = new ArrayList<>();
    for (ActorRef child : getContext().getChildren()) {
      children.add(ask(getSelf(), new GetEvent(child.path().name()), timeout)
          .thenApply(e -> (Optional<Event>) e).toCompletableFuture());
    }

    return CompletableFuture
        .allOf(children.toArray(new CompletableFuture[children.size()]))
        .thenApply(ignored -> {
          List<Event> list = children.stream()
              .map(CompletableFuture::join)
              .map(v -> v.get())
              .collect(Collectors.toList());
          return new Events(list);
        });
  }


  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(CreateEvent.class, createEvent -> {
          log.info("Received CreateEvent message: ");
          if (getContext().child(createEvent.name).isEmpty()) {
            create(createEvent.name, createEvent.tickets);
          } else {
            getContext().sender().tell(new EventExists(), self());
          }
//                    getContext().child(name).fold(proc(() -> create(createEvent.name, createEvent.tickets)),
//                            proc(ch -> getContext().sender().tell(new EventExists(), self())));
        }).match(GetTickets.class, getTickets -> {
          log.info("Received GetTickets message: ");
          Option<ActorRef> child = getContext().child(getTickets.event);
          if (child.isEmpty()) {
            notFound(getTickets.event);
          } else {
            buy(getTickets.tickets, child.get());
          }
        }).match(GetEvent.class, getEvent -> {
          log.info("Received GetEvent message: ");
          Option<ActorRef> child = getContext().child(getEvent.name);
          if (child.isEmpty()) {
            getContext().sender().tell(Optional.empty(), getSelf());
          } else {
            child.get().forward(new TicketSeller.GetEvent(), getContext());
          }
        }).match(GetEvents.class, getEvents -> {
          log.info("Received GetEvents message: ");

          pipe(getEvents(), getContext().dispatcher()).to(sender());
        }).match(CancelEvent.class, cancelEvent -> {
          log.info("Received CancelEvent message: ");
          Option<ActorRef> child = getContext().child(cancelEvent.name);
          if (child.isEmpty()) {
            getContext().sender().tell(Optional.empty(), getSelf());
          } else {
            child.get().forward(new TicketSeller.Cancel(), getContext());
          }
        }).build();
  }
}

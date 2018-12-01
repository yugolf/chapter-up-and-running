package com.goticks;

import akka.actor.AbstractActor;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import java.util.*;
import java.util.stream.*;

// アクタークラスの定義
public class TicketSeller extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  // propsの定義
  public static Props props(String event) {
    return Props.create(TicketSeller.class, () -> new TicketSeller(event));
  }

  private final String event;

  // コンストラクタ
  private TicketSeller(String event) {
    this.event = event;
  }

  // メッセージプロトコルの定義
  // ------------------------------------------>
  public static class Add extends AbstractMessage {
    private final List<Ticket> tickets;

    public Add(List<Ticket> tickets) {
      this.tickets = tickets;
    }

    public List<Ticket> getTickets() {
      return tickets;
    }
  }

  public static class Ticket extends AbstractMessage {
    private final int id;

    public Ticket(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }
  }

  public static class Tickets extends AbstractMessage {
    private final String event;
    private final List<Ticket> entries;

    public Tickets(String event, List<Ticket> entries) {
      this.event = event;
      this.entries = entries;
    }

    public Tickets(String event) {
      this.event = event;
      this.entries = new ArrayList<>();
    }

    public String getEvent() {
      return event;
    }

    public List<Ticket> getEntries() {
      return entries;
    }
  }

  public static class Buy extends AbstractMessage {
    private final int tickets;

    public Buy(int tickets) {
      this.tickets = tickets;
    }

    public int getTickets() {
      return tickets;
    }
  }

  public static class GetEvent extends AbstractMessage {
  }

  public static class Cancel extends AbstractMessage {
  }
  // <------------------------------------------

  private final List<Ticket> tickets = new ArrayList<>();

  // receiveメソッドの定義
  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(Add.class, add -> {
          log.debug("Received: {}", add);

          tickets.addAll(add.tickets);
        }).match(Buy.class, buy -> {
          log.debug("Received: {}", buy);

          List<Ticket> entries = tickets.stream().limit(buy.tickets).collect(Collectors.toList());

          if (entries.size() >= buy.tickets) {
            getContext().sender().tell(new Tickets(event, entries), getSelf());
            tickets.subList(0, buy.tickets).clear();
          } else {
            getContext().sender().tell(new Tickets(event), getSelf());
          }
        }).match(GetEvent.class, getEvent -> {
          log.debug("Received: {}", getEvent);

          sender().tell(Optional.of(new BoxOffice.Event(event, tickets.size())), self());
        }).match(Cancel.class, getCancel -> {
          log.debug("Received: {}", getCancel);

          sender().tell(Optional.of(new BoxOffice.Event(event, tickets.size())), self());
          self().tell(PoisonPill.getInstance(), self());
        }).build();
  }
}

package com.goticks;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

// アクタークラスの定義
public class TicketSeller extends AbstractActor {
  private LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  // propsの定義
  static Props props(String event) {
    return Props.create(TicketSeller.class, () -> new TicketSeller(event));
  }

  private String event;

  // コンストラクタ
  public TicketSeller(String event) {
    this.event = event;
  }

  // メッセージプロトコルの定義
  static public class Add {
    public final List<Ticket> tickets;

    public Add(List<Ticket> tickets) {
      this.tickets = tickets;
    }
  }

  static public class Ticket {
    public final int id;

    public Ticket(int id) {
      this.id = id;
    }
  }

  static public class Tickets {
    public String event;
    public List<Ticket> entries;

    public Tickets(String event, List<Ticket> entries) {
      this.event = event;
      this.entries = entries;
    }

    public Tickets(String event) {
      this.event = event;
      this.entries = new ArrayList<Ticket>();
    }
  }

  static public class Buy {
    public final int tickets;

    public Buy(int tickets) {
      this.tickets = tickets;
    }
  }

  static public class GetEvent {
  }

  static public class Cancel {
  }


  List<Ticket> tickets = new ArrayList<>();

  // receiveメソッドの定義
  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(Add.class, add -> {
          log.info("Received Add message: ");
          tickets.addAll(add.tickets);
        }).match(Buy.class, buy -> {
          log.info("Received Buy message: ");
          List<Ticket> entries = tickets
              .stream()
              .limit(buy.tickets)
              .collect(Collectors.toList());
          if (entries.size() >= buy.tickets) {
            getContext().sender().tell(new Tickets(event, entries), getSelf());
            tickets = tickets.subList(buy.tickets, tickets.size());
          } else {
            getContext().sender().tell(new Tickets(event), getSelf());
          }
        }).match(GetEvent.class, getEvent -> {
          log.info("Received GetEvent message: ");
          sender().tell(Optional.of(new BoxOffice.Event(event, tickets.size())), self());
        }).match(Cancel.class, getCancel -> {
          log.info("Received Cancel message: ");
          sender().tell(Optional.of(new BoxOffice.Event(event, tickets.size())), self());
          self().tell(PoisonPill.getInstance(), self());
        }).build();
  }
}

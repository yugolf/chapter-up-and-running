package com.goticks;


import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.goticks.BoxOffice.*;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.server.PathMatchers.segment;
import static akka.pattern.PatternsCS.ask;


public class RestApi extends AllDirectives {
  private final Long timeout;
  private final LoggingAdapter log;
  private final ActorRef boxOfficeActor;

  // コンストラクタ
  RestApi(ActorSystem system, Long timeout) {
    this.timeout = timeout;
    log = Logging.getLogger(system, this);
    boxOfficeActor = system.actorOf(BoxOffice.props(timeout), "boxOfficeActor");
  }

  private CompletionStage<Events> getEvents() {
    return ask(boxOfficeActor, new GetEvents(), timeout).thenApply((Events.class::cast));
  }

  @SuppressWarnings("unchecked")
  private CompletionStage<Optional<Event>> getEvent(String name) {
    return ask(boxOfficeActor, new GetEvent(name), timeout).thenApply(obj -> (Optional<Event>) obj);
  }

  @SuppressWarnings("unchecked")
  private CompletionStage<Optional<Event>> cancelEvent(String name) {
    return ask(boxOfficeActor, new CancelEvent(name), timeout).thenApply(obj -> (Optional<Event>) obj);
  }

  private CompletionStage<EventResponse> createEvent(String name, int nrOfTickets) {
    return ask(boxOfficeActor, new CreateEvent(name, nrOfTickets), timeout).thenApply(EventResponse.class::cast);
  }

  private CompletionStage<TicketSeller.Tickets> requestTickets(String event, int tickets) {
    return ask(boxOfficeActor, new GetTickets(event, tickets), timeout).thenApply(TicketSeller.Tickets.class::cast);
  }

  public Route createRoute() {
    return route(
        pathPrefix("events", () -> route(
            // [Get all events] GET /events
            get(() -> pathEndOrSingleSlash(() -> {
              log.debug("receive request: GET /events");

              final CompletionStage<Events> events = getEvents();
              return completeOKWithFuture(events, Jackson.marshaller());
            })),
            // [Get an event] GET /events/:event
            get(() -> pathPrefix(segment(), (String name) ->
                pathEndOrSingleSlash(() -> {
                  log.debug("receive request: GET /events/{}", name);

                  CompletionStage<Optional<Event>> futureEvent = getEvent(name);
                  return onSuccess(() -> futureEvent, maybeEvent -> {
                        if (maybeEvent.isPresent())
                          return completeOK(maybeEvent.get(), Jackson.marshaller());
                        else
                          return complete(StatusCodes.NOT_FOUND);
                      }
                  );
                }))),
            // [Create an event] POST /events/:event tickets:=10
            post(() -> pathPrefix(segment(), (String name) ->
                pathEndOrSingleSlash(() ->
                    entity(Jackson.unmarshaller(EventDescription.class), event -> {
                      log.debug("receive request: POST /events/{} tickets:={}", name, event.tickets);

                      CompletionStage<EventResponse> futureEventResponse = createEvent(name, event.tickets);
                      return onSuccess(() -> futureEventResponse, maybeEventResponse -> {
                            if (maybeEventResponse instanceof EventCreated) {
                              return complete(StatusCodes.CREATED, ((EventCreated) maybeEventResponse).getEvent(), Jackson.marshaller());
                            } else {
                              Error err = new Error(name + " exists already.");
                              return complete(StatusCodes.BAD_REQUEST, err, Jackson.marshaller());
                            }
                          }
                      );
                    })
                ))),
            // [Buy tickets] POST /events/:event/tickets
            post(() -> pathPrefix(segment().slash(segment("tickets")), (String event) ->
                pathEndOrSingleSlash(() ->
                    entity(Jackson.unmarshaller(TicketRequest.class), request -> {
                      log.debug("receive request: POST /events/{}/tickets", event);

                      CompletionStage<TicketSeller.Tickets> futureTickets = requestTickets(event, request.tickets);
                      return onSuccess(() -> futureTickets, maybeTickets -> {
                          System.out.println(maybeTickets.getEntries());
                            if (maybeTickets.getEntries().isEmpty())
                              return complete(StatusCodes.NOT_FOUND);
                            else
                              return complete(StatusCodes.CREATED, maybeTickets, Jackson.marshaller());
                          }
                      );
                    })
                ))),
            // [Cancel an event] DELETE /events/:event
            delete(() -> pathPrefix(segment(), (String name) ->
                pathEndOrSingleSlash(() -> {
                      log.debug("receive request: DELETE /events/{}", name);

                      CompletionStage<Optional<Event>> futureEvent = cancelEvent(name);
                      return onSuccess(() -> futureEvent, maybeEvent -> {
                            if (maybeEvent.isPresent())
                              return completeOK(maybeEvent.get(), Jackson.marshaller());
                             else
                              return complete(StatusCodes.NOT_FOUND);
                          }
                      );
                    }
                )))
        ))
    );


  }

  private static class EventDescription {
    final int tickets;

    @JsonCreator
    EventDescription(@JsonProperty("tickets") int tickets) {
      this.tickets = tickets;
    }

    public int getTickets() {
      return tickets;
    }
  }

  private static class TicketRequest {
    final int tickets;

    @JsonCreator
    TicketRequest(@JsonProperty("tickets") int tickets) {
      this.tickets = tickets;
    }

    public int getTickets() {
      return tickets;
    }
  }

  private static class Error {
    final String message;

    @JsonCreator
    Error(@JsonProperty("message") String message) {
      this.message = message;
    }

    public String getMessage() {
      return message;
    }
  }
}

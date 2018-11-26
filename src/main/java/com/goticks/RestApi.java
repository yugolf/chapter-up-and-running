package com.goticks;


import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.util.Timeout;

import com.goticks.BoxOffice.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;

import static akka.http.javadsl.server.PathMatchers.*;
import static akka.pattern.PatternsCS.ask;


public class RestApi extends AllDirectives {

  final ActorSystem system = ActorSystem.create("go-ticks");
  Long timeout = 2000L;
  final LoggingAdapter log = Logging.getLogger(system, RestApi.class);

  final ActorRef boxOfficeActor =
      system.actorOf(BoxOffice.props(timeout), "boxOfficeActor");


  private CompletionStage<Events> getEvents() {
    return ask(boxOfficeActor, new GetEvents(), timeout).thenApply((Events.class::cast));
  }

  private CompletionStage<Optional<Event>> getEvent(String event) {
    return ask(boxOfficeActor, new GetEvent(event), timeout).thenApply(e -> (Optional<Event>) e);
  }

  private CompletionStage<Optional<Event>> cancelEvent(String event) {
    return ask(boxOfficeActor, new CancelEvent(event), timeout).thenApply(e -> (Optional<Event>) e);
  }

  private CompletionStage<EventResponse> createEvent(String event, int nrOfTickets) {
    return ask(boxOfficeActor, new CreateEvent(event, nrOfTickets), timeout).thenApply(EventResponse.class::cast);
  }

  private CompletionStage<TicketSeller.Tickets> requestTickets(String event, int tickets) {
    return ask(boxOfficeActor, new GetTickets(event, tickets), timeout).thenApply(TicketSeller.Tickets.class::cast);
  }

  public Route createRoute() {
    return route(
        pathPrefix("events", () -> route(
            // [Get all events] GET /events
            get(() -> pathEndOrSingleSlash(() -> {
              log.info("receive request: GET /events");
              final CompletionStage<Events> events = getEvents();
              return completeOKWithFuture(events, Jackson.marshaller());
            })),
            // [Get an event] GET /events/:event
            get(() -> path(segment(), (String event) -> {
              log.info("receive request: GET /events/{}", event);
              CompletionStage<Optional<Event>> futureMaybeEvent = getEvent(event);
              return onSuccess(() -> futureMaybeEvent, maybeEvent ->
                  maybeEvent.map(ev -> completeOK(ev, Jackson.marshaller()))
                      .orElse(complete(StatusCodes.NOT_FOUND, "Not Found"))
              );
            })),
            // [Create an event] POST /events/:event tickets:=10
            post(() -> path(segment(), (String event) ->
                entity(Jackson.unmarshaller(EventDescription.class), ev -> {
                  log.info("receive request: POST /events/{} tickets:={}", event, ev.tickets);
                  CompletionStage<EventResponse> futureCreated = createEvent(event, ev.tickets);
                  return onSuccess(() -> futureCreated, created -> {
                        if (created instanceof EventCreated) {
                          return complete(StatusCodes.CREATED, ((EventCreated) created).event, Jackson.marshaller());
                        } else {
                          Error err = new Error(event + " exists already.");
                          return complete(StatusCodes.BAD_REQUEST, err, Jackson.marshaller());
                        }
                      }
                  );
                }))),
            // [Buy tickets] POST /events/:event/tickets
            post(() -> path(segment().slash(segment("tickets")), (String event) ->
                entity(Jackson.unmarshaller(TicketRequest.class), request -> {
                  log.info("receive request: POST /events/{}/tickets", event);
                  CompletionStage<TicketSeller.Tickets> futureMaybeTickets = requestTickets(event, request.tickets);
                  return onSuccess(() -> futureMaybeTickets, maybeTickets -> {
                        if (maybeTickets.entries.isEmpty()) {
                          return complete(StatusCodes.NOT_FOUND, "Not Found");
                        } else {
                          return complete(StatusCodes.CREATED, maybeTickets, Jackson.marshaller());
                        }
                      }
                  );
                }))),
            // [Cancel an event] DELETE /events/:event
            delete(() -> path(segment(), (String event) -> {
              log.info("receive request: DELETE /events/{}", event);
              CompletionStage<Optional<Event>> futureMaybeEvent = cancelEvent(event);
              return onSuccess(() -> futureMaybeEvent, maybeEvent ->
                  maybeEvent.map(ev -> completeOK(ev, Jackson.marshaller()))
                      .orElse(complete(StatusCodes.NOT_FOUND, "Not Found"))
              );
            }))
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

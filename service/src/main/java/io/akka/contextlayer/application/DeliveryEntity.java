package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

/**
 * The record of one notification and how it went. Id: {@code ScopedId.of(orgId, "nt", safe(subject))},
 * so a message that is processed twice is recorded once. Nothing in it is a record's contents.
 */
@Component(id = "delivery")
public class DeliveryEntity extends EventSourcedEntity<DeliveryEntity.State, DeliveryEntity.Event> {

  /** @param status DELIVERED, FAILED or NO_ROUTE */
  public record Delivery(String deliveryId, String orgId, String route, String type, String subject, String status,
      String detail, long at) {}

  public record State(Delivery delivery) {}

  public sealed interface Event {
    @TypeName("delivery-recorded")
    record Recorded(Delivery delivery) implements Event {}
  }

  @Override
  public State emptyState() {
    return new State(null);
  }

  public Effect<State> record(Delivery delivery) {
    if (currentState().delivery() != null) return effects().reply(currentState());
    return effects().persist(new Event.Recorded(delivery)).thenReply(state -> state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Override
  public State applyEvent(Event event) {
    return switch (event) {
      case Event.Recorded recorded -> new State(recorded.delivery());
    };
  }
}

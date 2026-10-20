package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.Integrity;

/**
 * One link of a project's evidence chain, held by its position so it can be read directly. Id:
 * {@code ScopedId.of(orgId, "link", seq)}. A link is written once. Writing the same link again
 * changes nothing; writing a different one for the same position is refused.
 */
@Component(id = "chain-link")
public class ChainLinkEntity extends EventSourcedEntity<ChainLinkEntity.State, ChainLinkEntity.Event> {

  public record State(Integrity.Link link) {}

  public sealed interface Event {
    @TypeName("link-written")
    record Written(Integrity.Link link) implements Event {}
  }

  @Override
  public State emptyState() {
    return new State(null);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  public Effect<State> write(Integrity.Link link) {
    var held = currentState().link();
    if (held != null) {
      return held.equals(link) ? effects().reply(currentState()) : effects().error("link " + link.seq() + " is already written differently");
    }
    return effects().persist(new Event.Written(link)).thenReply(s -> s);
  }

  @Override
  public State applyEvent(Event event) {
    return switch (event) {
      case Event.Written e -> new State(e.link());
    };
  }
}

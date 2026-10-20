package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.Evidence;

/**
 * An append-only record that a control ran. Its id is {@code ScopedId.of(orgId, "ev", controlId,
 * part)} with a part that is the same for the same fact, so recording a fact again changes nothing.
 * There is no command that alters or removes a record.
 */
@Component(id = "evidence")
public class EvidenceEntity extends EventSourcedEntity<EvidenceEntity.State, EvidenceEntity.Event> {

  public record State(Evidence evidence) {}

  public sealed interface Event {
    @TypeName("evidence-recorded")
    record Recorded(Evidence evidence) implements Event {}
  }

  @Override
  public State emptyState() {
    return new State(null);
  }

  public Effect<State> record(Evidence evidence) {
    if (currentState().evidence() != null) return effects().reply(currentState());
    return effects().persist(new Event.Recorded(evidence)).thenReply(state -> state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Override
  public State applyEvent(Event event) {
    return switch (event) {
      case Event.Recorded recorded -> new State(recorded.evidence());
    };
  }
}

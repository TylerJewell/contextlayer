package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.contextlayer.domain.ScopedId;

/**
 * Whether an assistant is halted. While it is, nothing it asks for is done and its playbooks stop.
 * Only the outcome of a halt or restart decision sets this (see {@link HumanEventApplier}); the
 * decision itself, with who made it and why, is the human event and its evidence. Id:
 * {@code ScopedId.of(orgId, "halt", assistantId)}.
 */
@Component(id = "assistant-halt")
public class HaltEntity extends KeyValueEntity<HaltEntity.State> {

  public record State(boolean halted, long since, String by, String reason, String eventId) {}

  public record Set(boolean halted, String by, String reason, String eventId, long now) {}

  public static String id(String orgId, String assistantId) {
    return ScopedId.of(orgId, "halt", assistantId);
  }

  public static boolean isHalted(ComponentClient client, String orgId, String assistantId) {
    return client.forKeyValueEntity(id(orgId, assistantId)).method(HaltEntity::get).invoke().halted();
  }

  @Override
  public State emptyState() {
    return new State(false, 0, "", "", "");
  }

  public Effect<State> set(Set command) {
    var state = new State(command.halted(), command.now(), command.by(), command.reason(), command.eventId());
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}

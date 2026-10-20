package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.contextlayer.governance.Compliance;

/**
 * Whether an organization has waived a control, and why. Only the current waiver is held; granting
 * and lifting are also recorded as evidence, which is the history. Id: {@code ScopedId.of(orgId,
 * "waiver", controlId)}.
 */
@Component(id = "control-waiver")
public class WaiverEntity extends KeyValueEntity<WaiverEntity.State> {

  /**
   * @param waivedUntil when the waiver lapses, or 0 for no end
   * @param lifted a waiver ended early by a person
   */
  public record State(boolean exists, String reason, String by, String authority, long grantedAt, long waivedUntil,
      boolean lifted) {}

  public record Grant(String reason, String by, String authority, long waivedUntil, long now) {}

  public record Lift(String by, String authority, long now) {}

  @Override
  public State emptyState() {
    return new State(false, "", "", "", 0, 0, false);
  }

  public Effect<State> grant(Grant command) {
    if (command.reason() == null || command.reason().isBlank()) return effects().error("a reason is required");
    if (command.waivedUntil() != 0 && command.waivedUntil() <= command.now()) {
      return effects().error("the waiver must end in the future");
    }
    var state = new State(true, command.reason().trim(), command.by(), command.authority(), command.now(),
        command.waivedUntil(), false);
    return effects().updateState(state).thenReply(state);
  }

  public Effect<State> lift(Lift command) {
    if (!currentState().exists() || currentState().lifted()) return effects().error("there is no waiver to lift");
    var s = currentState();
    var state = new State(true, s.reason(), s.by(), s.authority(), s.grantedAt(), s.waivedUntil(), true);
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  /** The waiver as compliance reads it: null when there is none or it was lifted. */
  public static Compliance.Waiver asWaiver(State state) {
    return state.exists() && !state.lifted() ? new Compliance.Waiver(state.reason(), state.by(), state.waivedUntil()) : null;
  }
}

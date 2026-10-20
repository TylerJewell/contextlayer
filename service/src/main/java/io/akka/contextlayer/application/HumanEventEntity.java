package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.Checked;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.HumanEventEvent;
import io.akka.contextlayer.governance.Refusal;
import io.akka.contextlayer.governance.Reviewer;
import java.util.List;

/**
 * One human event. Id: {@code ScopedId.of(orgId, "he", ...)}. Every rule is in {@link HumanEvent};
 * this class persists what the rules accept and answers what they refuse, so a refusal is a reply the
 * caller can tell apart, not an error.
 *
 * <p>Each command carries the time it was made, so the same rules decide the same way when events
 * are replayed and tests control the clock.
 */
@Component(id = "human-event")
public class HumanEventEntity extends EventSourcedEntity<HumanEvent, HumanEventEvent> {

  /** The event as it now stands, or the reason the command was not accepted. */
  public record Answer(HumanEvent event, Refusal refusal) {}

  public record Act(Reviewer reviewer, long now) {}

  public record Decide(
      Reviewer reviewer, String action, String rationale, List<String> checks, long presentedAt, long now) {}

  public record Escalate(Reviewer reviewer, String reason, long now) {}

  public record SendBack(Reviewer reviewer, String requested, long now) {}

  public record Withdraw(Reviewer reviewer, String reason, long now) {}

  public record Expire(long now) {}

  public record Applied(String note, long now) {}

  public record Extend(Reviewer reviewer, long extraMillis, String reason, long now) {}

  public record Supersede(String reason, long now) {}

  @Override
  public HumanEvent emptyState() {
    return HumanEvent.empty();
  }

  /** Raising the same event twice leaves the first as it is. */
  public Effect<Answer> raise(HumanEventEvent.Raised raised) {
    if (currentState().exists()) return effects().reply(new Answer(currentState(), null));
    return effects().persist(raised).thenReply(state -> new Answer(state, null));
  }

  public Effect<Answer> claim(Act command) {
    return handle(currentState().claim(command.reviewer(), command.now()));
  }

  public Effect<Answer> release(Act command) {
    return handle(currentState().release(command.reviewer(), command.now()));
  }

  public Effect<Answer> decide(Decide command) {
    return handle(currentState().decide(command.reviewer(), command.action(), command.rationale(), command.checks(),
        command.presentedAt(), command.now()));
  }

  public Effect<Answer> escalate(Escalate command) {
    return handle(currentState().escalate(command.reviewer(), command.reason(), command.now()));
  }

  public Effect<Answer> sendBack(SendBack command) {
    return handle(currentState().sendBack(command.reviewer(), command.requested(), command.now()));
  }

  public Effect<Answer> resubmit(Act command) {
    return handle(currentState().resubmit(command.reviewer(), command.now()));
  }

  public Effect<Answer> withdraw(Withdraw command) {
    return handle(currentState().withdraw(command.reviewer(), command.reason(), command.now()));
  }

  public Effect<Answer> extend(Extend command) {
    return handle(currentState().extend(command.reviewer(), command.extraMillis(), command.reason(), command.now()));
  }

  public Effect<Answer> supersede(Supersede command) {
    return handle(currentState().supersede(command.reason(), command.now()));
  }

  public Effect<Answer> expire(Expire command) {
    return handle(currentState().expire(command.now()));
  }

  public Effect<Answer> markApplied(Applied command) {
    return handle(currentState().markApplied(command.note(), command.now()));
  }

  public Effect<Answer> markApplyFailed(Applied command) {
    return handle(currentState().markApplyFailed(command.note(), command.now()));
  }

  public ReadOnlyEffect<HumanEvent> get() {
    return effects().reply(currentState());
  }

  @Override
  public HumanEvent applyEvent(HumanEventEvent event) {
    return currentState().apply(event);
  }

  private Effect<Answer> handle(Checked<HumanEventEvent> step) {
    if (step.refused()) return effects().reply(new Answer(currentState(), step.refusal()));
    if (step.value() == null) return effects().reply(new Answer(currentState(), null));
    return effects().persist(step.value()).thenReply(state -> new Answer(state, null));
  }
}

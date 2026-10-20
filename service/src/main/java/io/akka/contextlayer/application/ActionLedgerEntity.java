package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.ActionEvent;
import io.akka.contextlayer.domain.ActionPolicy;
import io.akka.contextlayer.domain.ActionRecord;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.domain.RiskClass;

/**
 * The idempotent record of one action. The id is {@code ScopedId.of(orgId, assistantId, actionKey)},
 * so repeating a request with the same key returns the existing entry and records nothing new.
 * Entities never call external systems; {@link ActionWorkflow} does, and records the outcome here.
 */
@Component(id = "action-ledger")
public class ActionLedgerEntity extends EventSourcedEntity<ActionRecord, ActionEvent> {

  public record Request(
      String orgId,
      String assistantId,
      String actionKey,
      String actionType,
      String payload,
      RiskClass risk,
      AssistantStage stage,
      String releaseId,
      boolean catalogued,
      boolean halted) {}

  public record Approve(String approvedBy) {}

  public record Reject(String rejectedBy, String reason) {}

  public record Finish(String detail) {}

  public record ResolveUnknown(String resolvedBy, boolean succeeded, String note) {}

  @Override
  public ActionRecord emptyState() {
    return ActionRecord.empty();
  }

  public Effect<ActionRecord> request(Request command) {
    if (currentState().exists()) return effects().reply(currentState());
    var decision = command.halted()
        ? ActionPolicy.halted()
        : command.catalogued()
        ? ActionPolicy.decide(command.stage(), !command.releaseId().isBlank(), command.risk())
        : ActionPolicy.notAvailable();
    return effects()
        .persist(new ActionEvent.Requested(
            command.orgId(), command.assistantId(), command.actionKey(), command.actionType(),
            command.payload(), command.risk(), command.stage(), command.releaseId(),
            decision.status(), decision.reason()))
        .thenReply(state -> state);
  }

  public Effect<ActionRecord> approve(Approve command) {
    var error = currentState().validateApprove(command.approvedBy());
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new ActionEvent.Approved(command.approvedBy())).thenReply(state -> state);
  }

  public Effect<ActionRecord> reject(Reject command) {
    var error = currentState().validateApprove(command.rejectedBy());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new ActionEvent.Rejected(command.rejectedBy(), command.reason()))
        .thenReply(state -> state);
  }

  public Effect<ActionRecord> start() {
    var error = currentState().validateStart();
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new ActionEvent.Started()).thenReply(state -> state);
  }

  public Effect<ActionRecord> succeed(Finish command) {
    var error = currentState().validateFinish();
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new ActionEvent.Succeeded(command.detail())).thenReply(state -> state);
  }

  public Effect<ActionRecord> fail(Finish command) {
    var error = currentState().validateFinish();
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new ActionEvent.Failed(command.detail())).thenReply(state -> state);
  }

  public Effect<ActionRecord> markOutcomeUnknown(Finish command) {
    var error = currentState().validateMarkUnknown();
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new ActionEvent.OutcomeUnknown(command.detail())).thenReply(state -> state);
  }

  /** A person decides what happened after an unknown outcome; the platform never retries it. */
  public Effect<ActionRecord> resolveUnknown(ResolveUnknown command) {
    var error = currentState().validateResolveUnknown(command.resolvedBy());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new ActionEvent.UnknownResolved(command.resolvedBy(), command.succeeded(), command.note()))
        .thenReply(state -> state);
  }

  public ReadOnlyEffect<ActionRecord> get() {
    if (!currentState().exists()) return effects().error("action not found");
    return effects().reply(currentState());
  }

  @Override
  public ActionRecord applyEvent(ActionEvent event) {
    return currentState().apply(event);
  }
}

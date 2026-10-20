package io.akka.contextlayer.domain;

import java.util.Optional;

/**
 * The ledger entry for one action. The entity id is derived from an idempotency key, so a
 * repeated request addresses the same entry and never produces a second effect (AST-06).
 */
public record ActionRecord(
    String orgId,
    String assistantId,
    String actionKey,
    String actionType,
    String payload,
    RiskClass risk,
    String releaseId,
    ActionStatus status,
    String reason,
    String decidedBy,
    boolean exists) {

  public static ActionRecord empty() {
    return new ActionRecord("", "", "", "", "", RiskClass.NEVER_BY_ASSISTANT, "",
        ActionStatus.DENIED, "", "", false);
  }

  public Optional<String> validateApprove(String approver) {
    if (!exists) return Optional.of("action not found");
    if (status != ActionStatus.AWAITING_APPROVAL) {
      return Optional.of("action is " + status + ", not awaiting approval");
    }
    if (approver == null || approver.isBlank()) return Optional.of("an approver is required");
    return Optional.empty();
  }

  public Optional<String> validateStart() {
    if (!exists) return Optional.of("action not found");
    if (status != ActionStatus.APPROVED) {
      return Optional.of("action is " + status + ", not approved to run");
    }
    return Optional.empty();
  }

  public Optional<String> validateFinish() {
    if (!exists) return Optional.of("action not found");
    if (status != ActionStatus.IN_FLIGHT) {
      return Optional.of("action is " + status + ", not in flight");
    }
    return Optional.empty();
  }

  public Optional<String> validateMarkUnknown() {
    if (!exists) return Optional.of("action not found");
    if (status != ActionStatus.IN_FLIGHT && status != ActionStatus.APPROVED) {
      return Optional.of("action is " + status + "; only a started action can have an unknown outcome");
    }
    return Optional.empty();
  }

  public Optional<String> validateResolveUnknown(String resolver) {
    if (!exists) return Optional.of("action not found");
    if (status != ActionStatus.OUTCOME_UNKNOWN) {
      return Optional.of("action is " + status + ", not awaiting a decision");
    }
    if (resolver == null || resolver.isBlank()) return Optional.of("a resolver is required");
    return Optional.empty();
  }

  public ActionRecord apply(ActionEvent event) {
    return switch (event) {
      case ActionEvent.Requested e -> new ActionRecord(e.orgId(), e.assistantId(), e.actionKey(),
          e.actionType(), e.payload(), e.risk(), e.releaseId(), e.status(), e.reason(), "", true);
      case ActionEvent.Approved e -> with(ActionStatus.APPROVED, "approved by a person", e.approvedBy());
      case ActionEvent.Rejected e -> with(ActionStatus.REJECTED, e.reason(), e.rejectedBy());
      case ActionEvent.Started e -> with(ActionStatus.IN_FLIGHT, reason, decidedBy);
      case ActionEvent.Succeeded e -> with(ActionStatus.SUCCEEDED, e.detail(), decidedBy);
      case ActionEvent.Failed e -> with(ActionStatus.FAILED, e.detail(), decidedBy);
      case ActionEvent.OutcomeUnknown e -> with(ActionStatus.OUTCOME_UNKNOWN, e.detail(), decidedBy);
      case ActionEvent.UnknownResolved e -> with(
          e.succeeded() ? ActionStatus.SUCCEEDED : ActionStatus.FAILED, e.note(), e.resolvedBy());
    };
  }

  private ActionRecord with(ActionStatus next, String nextReason, String nextDecidedBy) {
    return new ActionRecord(orgId, assistantId, actionKey, actionType, payload, risk, releaseId,
        next, nextReason, nextDecidedBy, true);
  }
}

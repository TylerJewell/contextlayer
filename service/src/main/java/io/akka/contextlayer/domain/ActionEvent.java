package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface ActionEvent {

  @TypeName("action-requested")
  record Requested(
      String orgId,
      String assistantId,
      String actionKey,
      String actionType,
      String payload,
      RiskClass risk,
      AssistantStage stage,
      String releaseId,
      ActionStatus status,
      String reason)
      implements ActionEvent {}

  @TypeName("action-approved")
  record Approved(String approvedBy) implements ActionEvent {}

  @TypeName("action-rejected")
  record Rejected(String rejectedBy, String reason) implements ActionEvent {}

  @TypeName("action-started")
  record Started() implements ActionEvent {}

  @TypeName("action-succeeded")
  record Succeeded(String detail) implements ActionEvent {}

  @TypeName("action-failed")
  record Failed(String detail) implements ActionEvent {}

  @TypeName("action-outcome-unknown")
  record OutcomeUnknown(String detail) implements ActionEvent {}

  @TypeName("action-unknown-resolved")
  record UnknownResolved(String resolvedBy, boolean succeeded, String note)
      implements ActionEvent {}
}

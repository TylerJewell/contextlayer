package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface AssistantEvent {

  @TypeName("assistant-created")
  record Created(String orgId, String assistantId, String purpose, String owner)
      implements AssistantEvent {}

  @TypeName("assistant-release-added")
  record ReleaseAdded(String releaseId, String definition, String addedBy)
      implements AssistantEvent {}

  @TypeName("assistant-release-approved")
  record ReleaseApproved(String releaseId, String approvedBy) implements AssistantEvent {}

  @TypeName("assistant-stage-changed")
  record StageChanged(AssistantStage stage, String changedBy) implements AssistantEvent {}
}

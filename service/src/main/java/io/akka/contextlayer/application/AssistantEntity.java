package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.Assistant;
import io.akka.contextlayer.domain.AssistantEvent;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.domain.Playbook;

/** Entity id: {@code ScopedId.of(orgId, assistantId)}. */
@Component(id = "assistant")
public class AssistantEntity extends EventSourcedEntity<Assistant, AssistantEvent> {

  public record Create(String orgId, String assistantId, String purpose, String owner) {}

  public record AddRelease(String definition, String addedBy) {}

  public record ApproveRelease(String releaseId, String approvedBy) {}

  public record ChangeStage(AssistantStage stage, String changedBy) {}

  @Override
  public Assistant emptyState() {
    return Assistant.empty();
  }

  public Effect<Done> create(Create command) {
    var error = currentState().validateCreate(command.purpose(), command.owner());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new AssistantEvent.Created(
            command.orgId(), command.assistantId(), command.purpose(), command.owner()))
        .thenReply(state -> Done.getInstance());
  }

  /** Returns the release id. Adding a definition that already exists is a no-op. */
  public Effect<String> addRelease(AddRelease command) {
    var error = currentState().validateAddRelease(command.definition());
    if (error.isPresent()) return effects().error(error.get());
    try {
      Playbook.parse(command.definition());
    } catch (IllegalArgumentException e) {
      return effects().error(e.getMessage());
    }
    var releaseId = Assistant.releaseIdFor(command.definition());
    if (currentState().find(releaseId).isPresent()) return effects().reply(releaseId);
    return effects()
        .persist(new AssistantEvent.ReleaseAdded(releaseId, command.definition(), command.addedBy()))
        .thenReply(state -> releaseId);
  }

  public Effect<Done> approveRelease(ApproveRelease command) {
    var error = currentState().validateApprove(command.releaseId(), command.approvedBy());
    if (error.isPresent()) return effects().error(error.get());
    if (currentState().find(command.releaseId()).orElseThrow().approved()) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new AssistantEvent.ReleaseApproved(command.releaseId(), command.approvedBy()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> changeStage(ChangeStage command) {
    var error = currentState().validateStage(command.stage(), command.changedBy());
    if (error.isPresent()) return effects().error(error.get());
    if (currentState().stage() == command.stage()) return effects().reply(Done.getInstance());
    return effects()
        .persist(new AssistantEvent.StageChanged(command.stage(), command.changedBy()))
        .thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<Assistant> get() {
    if (!currentState().exists()) return effects().error("playbook not found");
    return effects().reply(currentState());
  }

  @Override
  public Assistant applyEvent(AssistantEvent event) {
    return currentState().apply(event);
  }
}

package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.CaseEvent;
import io.akka.contextlayer.domain.CaseFile;
import java.util.List;
import java.util.Map;

/** Entity id: {@code ScopedId.of(orgId, monitorId, hashOfRowKey, "r<run>")}, derived by the monitor. */
@Component(id = "case")
public class CaseEntity extends EventSourcedEntity<CaseFile, CaseEvent> {

  public record Open(
      String orgId,
      String caseId,
      String monitorId,
      int monitorVersion,
      String key,
      Map<String, Object> evidence,
      List<String> sourceNotes,
      String routeTo,
      String assistantId,
      long at) {}

  public record Close(String by, String note, long at) {}

  public record Annotate(String actor, String text, long at) {}

  @Override
  public CaseFile emptyState() {
    return CaseFile.empty();
  }

  /** Opening an existing case does nothing, so a retried monitor run never opens a second one. */
  public Effect<Done> open(Open command) {
    if (currentState().exists()) return effects().reply(Done.getInstance());
    return effects()
        .persist(new CaseEvent.Opened(command.orgId(), command.caseId(), command.monitorId(), command.monitorVersion(),
            command.key(), command.evidence(), command.sourceNotes(), command.routeTo(), command.assistantId(),
            command.at()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> noteNoLongerMatching(Long at) {
    var error = currentState().validateNoLongerMatching();
    if (error.isPresent()) return effects().reply(Done.getInstance());
    return effects().persist(new CaseEvent.NoLongerMatching(at)).thenReply(state -> Done.getInstance());
  }

  public Effect<CaseFile> resolve(Close command) {
    var error = currentState().validateClose(command.by());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new CaseEvent.Resolved(command.by(), command.note(), command.at()))
        .thenReply(state -> state);
  }

  public Effect<CaseFile> dismiss(Close command) {
    var error = currentState().validateClose(command.by());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new CaseEvent.Dismissed(command.by(), command.note(), command.at()))
        .thenReply(state -> state);
  }

  public Effect<CaseFile> annotate(Annotate command) {
    var error = currentState().validateAnnotate(command.actor(), command.text());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new CaseEvent.Annotated(command.actor(), command.text(), command.at()))
        .thenReply(state -> state);
  }

  /** Always replies; {@code exists() == false} means no such case. */
  public ReadOnlyEffect<CaseFile> get() {
    return effects().reply(currentState());
  }

  @Override
  public CaseFile applyEvent(CaseEvent event) {
    return currentState().apply(event);
  }
}

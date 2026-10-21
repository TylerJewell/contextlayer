package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.Monitor;
import io.akka.contextlayer.domain.MonitorDefinition;
import io.akka.contextlayer.domain.MonitorEvent;
import java.util.List;

/** Entity id: {@code ScopedId.of(orgId, monitorId)}. */
@Component(id = "monitor")
public class MonitorEntity extends EventSourcedEntity<Monitor, MonitorEvent> {

  public record Create(String orgId, String monitorId, MonitorDefinition definition, String by) {}

  public record Update(MonitorDefinition definition, String by) {}

  public record SetEnabled(boolean enabled, String by) {}

  /**
   * @param at identifies the run: recording the same run twice returns the first result
   * @param complete false when any source could not be read, so no case may be closed from this run
   */
  public record RecordRun(long at, List<String> matchingKeys, boolean complete, String note) {}

  @Override
  public Monitor emptyState() {
    return Monitor.empty();
  }

  public Effect<Done> create(Create command) {
    var error = currentState().validateCreate(command.definition(), command.by());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new MonitorEvent.Created(command.orgId(), command.monitorId(), command.definition(), command.by()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> update(Update command) {
    var error = currentState().validateUpdate(command.definition(), command.by());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new MonitorEvent.DefinitionUpdated(command.definition(), command.by()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> setEnabled(SetEnabled command) {
    var error = currentState().validateChange(command.by());
    if (error.isPresent()) return effects().error(error.get());
    if (currentState().enabled() == command.enabled()) return effects().reply(Done.getInstance());
    return effects()
        .persist(new MonitorEvent.EnabledChanged(command.enabled(), command.by()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Monitor.RunPlan> recordRun(RecordRun command) {
    if (!currentState().exists()) return effects().error("watcher not found");
    if (command.at() == currentState().lastRunAt()) return effects().reply(currentState().lastPlan());
    var plan = currentState().plan(command.matchingKeys(), command.complete());
    return effects()
        .persist(new MonitorEvent.RunRecorded(plan.runNumber(), command.at(), command.complete(), plan.newCases(),
            plan.goneCases(), command.note()))
        .thenReply(state -> plan);
  }

  public ReadOnlyEffect<Monitor> get() {
    return effects().reply(currentState());
  }

  @Override
  public Monitor applyEvent(MonitorEvent event) {
    return currentState().apply(event);
  }
}

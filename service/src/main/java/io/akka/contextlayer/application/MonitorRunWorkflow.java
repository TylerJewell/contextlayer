package io.akka.contextlayer.application;

import static java.time.Duration.ofMinutes;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.contextlayer.domain.Monitor;
import io.akka.contextlayer.domain.ScopedId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One run of a monitor: ask its question, record which rows newly match or have stopped
 * matching, open a case for each new row and close the case of each row that stopped.
 *
 * <p>Each step can be repeated safely, so a failure part-way through is retried to completion
 * and never loses or duplicates a case. Id: {@code ScopedId.of(orgId, monitorId, "t<scheduledAt>")},
 * so the same scheduled run is the same workflow.
 */
@Component(id = "monitor-run")
public class MonitorRunWorkflow extends Workflow<MonitorRunWorkflow.Run> {

  private static final long QUESTION_TIME_BUDGET_MILLIS = 20_000;

  public record Start(String orgId, String monitorId, long at) {}

  public record Run(
      String orgId,
      String monitorId,
      long at,
      String phase,
      String routeTo,
      String assistantId,
      List<String> keys,
      Map<String, Map<String, Object>> evidence,
      boolean complete,
      String note,
      List<String> sourceNotes,
      Monitor.RunPlan plan) {

    Run withPhase(String next) {
      return new Run(orgId, monitorId, at, next, routeTo, assistantId, keys, evidence, complete, note, sourceNotes, plan);
    }

    Run withAnswer(String routeTo, String assistantId, List<String> keys, Map<String, Map<String, Object>> evidence,
        boolean complete, String note, List<String> sourceNotes) {
      return new Run(orgId, monitorId, at, "ASKED", routeTo, assistantId, keys, evidence, complete, note, sourceNotes, plan);
    }

    Run withPlan(Monitor.RunPlan next) {
      return new Run(orgId, monitorId, at, "RECORDED", routeTo, assistantId, keys, evidence, complete, note, sourceNotes, next);
    }
  }

  private final ComponentClient componentClient;
  private final QuestionService questions;

  public MonitorRunWorkflow(ComponentClient componentClient, EntityStore store, SourceCaller sourceCaller) {
    this.componentClient = componentClient;
    this.questions = QuestionService.standard(componentClient, store, sourceCaller, QUESTION_TIME_BUDGET_MILLIS);
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(ofMinutes(2)).build();
  }

  public Effect<String> start(Start command) {
    if (currentState() != null) return effects().reply(currentState().phase());
    return effects()
        .updateState(new Run(command.orgId(), command.monitorId(), command.at(), "STARTED", "", "", List.of(), Map.of(),
            false, "", List.of(), null))
        .transitionTo(MonitorRunWorkflow::askStep)
        .thenReply("STARTED");
  }

  public ReadOnlyEffect<Run> get() {
    if (currentState() == null) return effects().error("run not found");
    return effects().reply(currentState());
  }

  private StepEffect askStep() {
    var run = currentState();
    var monitor = componentClient.forEventSourcedEntity(monitorEntityId(run)).method(MonitorEntity::get).invoke();
    if (!monitor.exists() || !monitor.enabled()) {
      return stepEffects().updateState(run.withPhase("SKIPPED")).thenEnd();
    }
    var definition = monitor.definition();
    // A monitor reads for whoever set it up, so it stops when they are no longer granted what it reads. Nothing is
    // recorded as a run and no case is opened or closed, because an answer that was not read says nothing.
    var domains = DomainAccess.config(componentClient, run.orgId());
    if (domains.governed()) {
      var access = DomainAccess.check(componentClient, run.orgId(), domains, definition.owner(),
          Set.copyOf(definition.ownerRoles()), "monitor " + run.monitorId() + " for " + definition.owner(), definition.entityTypes());
      if (!access.allowed() || definition.owner().isBlank()) return stepEffects().updateState(run.withPhase("REFUSED")).thenEnd();
    }
    var answer = questions.ask(run.orgId(), definition.question());

    var keys = new ArrayList<String>();
    var evidence = new LinkedHashMap<String, Map<String, Object>>();
    for (var row : answer.rows()) {
      var key = row.get(definition.keyField());
      if (key == null) continue;
      keys.add(String.valueOf(key));
      evidence.putIfAbsent(String.valueOf(key), row);
    }

    var problems = new ArrayList<String>();
    var sourceNotes = new ArrayList<String>();
    for (var source : answer.sources()) {
      sourceNotes.add(source.pack() + "/" + source.entityType() + ": " + source.freshness());
      if (source.freshness() == QuestionService.Freshness.STALE
          || source.freshness() == QuestionService.Freshness.UNAVAILABLE) {
        problems.add(source.pack() + "/" + source.entityType() + " was " + source.freshness());
      }
    }
    if (answer.rows().size() >= definition.question().limit()) {
      problems.add("the result reached its limit of " + definition.question().limit() + " rows");
    }
    var note = problems.isEmpty() ? "complete" : "incomplete: " + String.join("; ", problems);

    return stepEffects()
        .updateState(run.withAnswer(definition.routeTo(), definition.assistantId(), List.copyOf(keys),
            Map.copyOf(evidence), problems.isEmpty(), note, List.copyOf(sourceNotes)))
        .thenTransitionTo(MonitorRunWorkflow::recordStep);
  }

  private StepEffect recordStep() {
    var run = currentState();
    var plan = componentClient.forEventSourcedEntity(monitorEntityId(run))
        .method(MonitorEntity::recordRun)
        .invoke(new MonitorEntity.RecordRun(run.at(), run.keys(), run.complete(), run.note()));
    return stepEffects().updateState(run.withPhase("RECORDED").withPlan(plan)).thenTransitionTo(MonitorRunWorkflow::openCasesStep);
  }

  private StepEffect openCasesStep() {
    var run = currentState();
    for (var entry : run.plan().newCases().entrySet()) {
      componentClient.forEventSourcedEntity(entry.getValue())
          .method(CaseEntity::open)
          .invoke(new CaseEntity.Open(run.orgId(), entry.getValue(), run.monitorId(), run.plan().monitorVersion(),
              entry.getKey(), run.evidence().getOrDefault(entry.getKey(), Map.of()), run.sourceNotes(), run.routeTo(),
              run.assistantId(), run.at()));
      if (!run.assistantId().isBlank()) {
        componentClient.forWorkflow(entry.getValue())
            .method(PlaybookWorkflow::start)
            .invoke(new PlaybookWorkflow.Start(run.orgId(), entry.getValue()));
      }
    }
    return stepEffects().updateState(run.withPhase("CASES_OPENED")).thenTransitionTo(MonitorRunWorkflow::closeCasesStep);
  }

  private StepEffect closeCasesStep() {
    var run = currentState();
    for (var caseId : run.plan().goneCases().values()) {
      componentClient.forEventSourcedEntity(caseId).method(CaseEntity::noteNoLongerMatching).invoke(run.at());
    }
    return stepEffects().updateState(run.withPhase("DONE")).thenEnd();
  }

  private static String monitorEntityId(Run run) {
    return ScopedId.of(run.orgId(), run.monitorId());
  }
}

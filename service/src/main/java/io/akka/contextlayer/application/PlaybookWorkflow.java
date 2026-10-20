package io.akka.contextlayer.application;

import static java.time.Duration.ofSeconds;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.contextlayer.domain.Playbook;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.PersonalDataMasker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs an assistant's playbook for one case: label the case with a model, branch on the label,
 * request actions, and write each of those to the case timeline.
 *
 * <p>The release that runs is the one the assistant is allowed to run right now: a Live assistant
 * runs only an approved release, a Shadow assistant its newest, and a Draft assistant nothing.
 * Actions go through the same policy as any other request, so a Shadow assistant's actions are
 * recorded as "would do" and never sent. Id: {@code ScopedId.of(orgId, caseId)}.
 */
@Component(id = "playbook-run")
public class PlaybookWorkflow extends Workflow<PlaybookWorkflow.Run> {

  public record Start(String orgId, String caseId) {}

  public record Run(
      String orgId,
      String caseId,
      String assistantId,
      String releaseId,
      String definition,
      String current,
      Map<String, String> labels,
      Map<String, Object> evidence,
      String phase) {

    Run withPhase(String next) {
      return new Run(orgId, caseId, assistantId, releaseId, definition, current, labels, evidence, next);
    }

    Run loaded(String assistantId, String releaseId, String definition, String first, Map<String, Object> evidence) {
      return new Run(orgId, caseId, assistantId, releaseId, definition, first, labels, evidence, "RUNNING");
    }

    Run at(String step, Map<String, String> nextLabels) {
      return new Run(orgId, caseId, assistantId, releaseId, definition, step, nextLabels, evidence, phase);
    }
  }

  private final ComponentClient componentClient;

  public PlaybookWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(ofSeconds(60))
        .stepRecovery(PlaybookWorkflow::runStep, RecoverStrategy.maxRetries(2).failoverTo(PlaybookWorkflow::failedStep))
        .build();
  }

  public Effect<String> start(Start command) {
    if (currentState() != null) return effects().reply(currentState().phase());
    return effects()
        .updateState(new Run(command.orgId(), command.caseId(), "", "", "", "", Map.of(), Map.of(), "STARTED"))
        .transitionTo(PlaybookWorkflow::loadStep)
        .thenReply("STARTED");
  }

  public ReadOnlyEffect<Run> get() {
    if (currentState() == null) return effects().error("playbook run not found");
    return effects().reply(currentState());
  }

  private StepEffect loadStep() {
    var run = currentState();
    var file = componentClient.forEventSourcedEntity(run.caseId()).method(CaseEntity::get).invoke();
    if (!file.exists() || file.assistantId().isBlank()) {
      return stepEffects().updateState(run.withPhase("NO_ASSISTANT")).thenEnd();
    }
    var assistant = componentClient.forEventSourcedEntity(ScopedId.of(run.orgId(), file.assistantId()))
        .method(AssistantEntity::get).invoke();
    var release = assistant.runnableRelease();
    if (release.isEmpty()) {
      note(run, file.assistantId(), "playbook " + file.assistantId() + " is " + assistant.stage()
          + " with no release it may run, so it was not run");
      return stepEffects().updateState(run.withPhase("NOT_RUNNABLE")).thenEnd();
    }

    Playbook playbook;
    try {
      playbook = Playbook.parse(release.get().definition());
    } catch (IllegalArgumentException e) {
      note(run, file.assistantId(), "release " + release.get().releaseId() + " is not a valid playbook: " + e.getMessage());
      return stepEffects().updateState(run.withPhase("INVALID")).thenEnd();
    }
    note(run, file.assistantId(), "started release " + release.get().releaseId() + " (" + assistant.stage() + ")");
    return stepEffects()
        .updateState(run.loaded(file.assistantId(), release.get().releaseId(), release.get().definition(),
            playbook.first(), file.evidence()))
        .thenTransitionTo(PlaybookWorkflow::runStep);
  }

  /** Runs the current step, then moves to the next; it re-enters itself until the playbook ends. */
  private StepEffect runStep() {
    var run = currentState();
    if (Playbook.END.equals(run.current())) {
      return stepEffects().updateState(run.withPhase("DONE")).thenEnd();
    }
    if (HaltEntity.isHalted(componentClient, run.orgId(), run.assistantId())) {
      note(run, run.assistantId(), "playbook " + run.assistantId() + " is halted; it stopped before step " + run.current());
      return stepEffects().updateState(run.withPhase("HALTED")).thenEnd();
    }
    var playbook = Playbook.parse(run.definition());
    var step = playbook.step(run.current());
    var labels = new HashMap<>(run.labels());

    String next;
    switch (step.type()) {
      case "classify" -> {
        var judged = classify(run, step);
        labels.put(step.store(), judged.label());
        note(run, run.assistantId(), "judged " + step.store() + " = " + judged.label() + " (" + step.fields().size()
            + " field(s) shown to the model" + (judged.interaction().isEmpty() ? "" : ", " + judged.interaction()) + ")");
        next = step.next();
      }
      case "branch" -> {
        var value = labels.get(step.var());
        next = value != null && step.cases().containsKey(value) ? step.cases().get(value) : step.otherwise();
      }
      case "act" -> {
        var payload = Playbook.fill(step.payload(), run.evidence(), labels);
        var actionKey = "pb-" + shortHash(run.caseId() + "/" + step.id());
        componentClient.forWorkflow(ScopedId.of(run.orgId(), run.assistantId(), actionKey))
            .method(ActionWorkflow::start)
            .invoke(new ActionWorkflow.Start(run.orgId(), run.assistantId(), actionKey, step.actionType(), payload,
                run.caseId(), "system:playbook"));
        next = step.next();
      }
      case "annotate" -> {
        note(run, run.assistantId(), Playbook.fill(step.text(), run.evidence(), labels));
        next = step.next();
      }
      default -> throw new IllegalStateException("unknown step type " + step.type());
    }
    return stepEffects().updateState(run.at(next, labels)).thenTransitionTo(PlaybookWorkflow::runStep);
  }

  /** The model may only see the evidence fields the step lists, and only an allowed label is accepted. */
  /** What a classify step decided, and the runtime's record of the model call that produced it. */
  private record Judged(String label, String interaction) {}

  /**
   * Asks the model for a label. Personal data is masked in what the model is shown, and the answer
   * must be one of the step's labels; both are recorded as evidence, linked to the runtime's ledger
   * record of the call when the runtime reports one.
   */
  private Judged classify(Run run, Playbook.Step step) {
    var masked = PersonalDataMasker.mask(step.fields().stream()
        .map(f -> f + ": " + (run.evidence().containsKey(f) ? run.evidence().get(f) : "(not available)"))
        .collect(Collectors.joining("\n")));
    var at = System.currentTimeMillis();
    var part = shortHash(run.caseId() + "/" + step.id()) + "~" + at;
    var recorder = new EvidenceRecorder(componentClient);
    String interaction = "";
    try {
      var reply = componentClient.forAgent()
          .inSession(ScopedId.of(run.orgId(), "pb", shortHash(run.caseId() + "/" + step.id())))
          .method(ClassifierAgent::classify)
          .withDetailedReply()
          .invoke(new ClassifierAgent.Request(step.instruction(), masked.text(), step.labels()));
      interaction = reply.interactionId().map(id -> "ledger:" + id).orElse("");
      var judgment = reply.value();
      boolean allowed = judgment != null && step.labels().contains(judgment.label());
      recorder.record(new EvidenceRecorder.Fact(run.orgId(), Controls.LABEL_ALLOWLIST, part, EvaluationMode.ENFORCING,
          allowed ? "PASS" : "BLOCK", allowed ? "CONTINUED" : "BLOCKED", allowed ? "label-allowed" : "label-not-allowed",
          run.caseId() + "/" + step.id(), "system:playbook", "system", interaction, false, "", at));
      if (!allowed) {
        throw new IllegalStateException("the model answered with a label that is not allowed: "
            + (judgment == null ? "nothing" : judgment.label()));
      }
      return new Judged(judgment.label(), interaction);
    } finally {
      recorder.record(new EvidenceRecorder.Fact(run.orgId(), Controls.MASK_PERSONAL_DATA, part, EvaluationMode.ENFORCING,
          "PASS", masked.total() > 0 ? "MASKED" : "CONTINUED", masked.summary(), run.caseId() + "/" + step.id(),
          "system:playbook", "system", interaction, false, "", at));
    }
  }

  private StepEffect failedStep() {
    var run = currentState();
    note(run, run.assistantId(), "stopped at step " + run.current() + ": it failed after retries; a person should take over");
    return stepEffects().updateState(run.withPhase("FAILED")).thenEnd();
  }

  /** Writes to the case timeline; the timeline never stops the playbook. */
  private void note(Run run, String assistantId, String text) {
    try {
      componentClient.forEventSourcedEntity(run.caseId()).method(CaseEntity::annotate)
          .invoke(new CaseEntity.Annotate("playbook " + assistantId, text, System.currentTimeMillis()));
    } catch (RuntimeException e) {
      // intentionally ignored: see above
    }
  }

  private static String shortHash(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

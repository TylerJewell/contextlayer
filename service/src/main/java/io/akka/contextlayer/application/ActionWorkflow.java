package io.akka.contextlayer.application;

import static java.time.Duration.ofSeconds;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.contextlayer.domain.ActionCatalog;
import io.akka.contextlayer.domain.ActionPolicy;
import io.akka.contextlayer.domain.ActionStatus;
import io.akka.contextlayer.domain.RiskClass;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.HumanEvents;
import io.akka.contextlayer.governance.PersonalDataMasker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Takes one requested action from the assistant's request to its outcome: decide by policy,
 * wait for a person when required, call the outside system once, record the result.
 *
 * <p>The execute step never retries. If it fails in a way that leaves the outcome unknown (an
 * exception, a timeout), the run moves to the unknown-outcome step, which records that fact for
 * a person to resolve. Id: {@code ScopedId.of(orgId, assistantId, actionKey)}, the same as the
 * ledger entry.
 */
@Component(id = "action-run")
public class ActionWorkflow extends Workflow<ActionWorkflow.Run> {

  public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";

  /**
   * @param caseId the case this action belongs to, or empty; the case timeline records what happens to it
   * @param requestedBy the person who asked for the action, or {@code system:<part>}; they may not approve it
   */
  public record Start(
      String orgId, String assistantId, String actionKey, String actionType, String payload, String caseId,
      String requestedBy) {}

  public record Rejection(String by, String reason) {}

  public record Run(
      String orgId,
      String assistantId,
      String actionKey,
      String actionType,
      String payload,
      String caseId,
      String phase,
      String decidedBy,
      String rejectReason,
      String requestedBy) {

    Run withPhase(String next) {
      return new Run(orgId, assistantId, actionKey, actionType, payload, caseId, next, decidedBy, rejectReason,
          requestedBy);
    }

    Run withDecision(String by, String reason) {
      return new Run(orgId, assistantId, actionKey, actionType, payload, caseId, phase, by, reason, requestedBy);
    }
  }

  private final ComponentClient componentClient;
  private final ActionExecutor executor;

  public ActionWorkflow(ComponentClient componentClient, ActionExecutor executor) {
    this.componentClient = componentClient;
    this.executor = executor;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(ofSeconds(15))
        .stepRecovery(
            ActionWorkflow::executeStep,
            RecoverStrategy.maxRetries(0).failoverTo(ActionWorkflow::unknownOutcomeStep))
        .build();
  }

  public Effect<String> start(Start command) {
    if (currentState() != null) return effects().reply(currentState().phase());
    return effects()
        .updateState(new Run(command.orgId(), command.assistantId(), command.actionKey(),
            command.actionType(), command.payload(), command.caseId() == null ? "" : command.caseId(), "STARTED", "", "",
            command.requestedBy() == null ? "" : command.requestedBy()))
        .transitionTo(ActionWorkflow::decideStep)
        .thenReply("STARTED");
  }

  /**
   * Applies a person's approval. It is called when the human event is decided, which can be
   * delivered more than once, so a run already past the approval answers with where it is.
   */
  public Effect<String> approve(String approver) {
    if (currentState() == null) return effects().error("action is not awaiting approval");
    if (pastApproval(currentState().phase())) return effects().reply(currentState().phase());
    if (!AWAITING_APPROVAL.equals(currentState().phase())) return effects().error("action is not awaiting approval");
    return effects()
        .updateState(currentState().withDecision(approver, "").withPhase("APPROVING"))
        .transitionTo(ActionWorkflow::recordApprovalStep)
        .thenReply("APPROVING");
  }

  public Effect<String> reject(Rejection rejection) {
    if (currentState() == null) return effects().error("action is not awaiting approval");
    if (pastApproval(currentState().phase())) return effects().reply(currentState().phase());
    if (!AWAITING_APPROVAL.equals(currentState().phase())) return effects().error("action is not awaiting approval");
    return effects()
        .updateState(currentState().withDecision(rejection.by(), rejection.reason()).withPhase("REJECTING"))
        .transitionTo(ActionWorkflow::recordRejectionStep)
        .thenReply("REJECTING");
  }

  public ReadOnlyEffect<Run> get() {
    if (currentState() == null) return effects().error("action not found");
    return effects().reply(currentState());
  }

  private StepEffect decideStep() {
    var run = currentState();
    var resolvedRisk = resolveRisk(run);
    var risk = resolvedRisk.orElse(RiskClass.NEVER_BY_ASSISTANT);
    var assistant = componentClient
        .forEventSourcedEntity(ScopedId.of(run.orgId(), run.assistantId()))
        .method(AssistantEntity::get)
        .invoke();
    var releaseId = assistant.runnableRelease().map(r -> r.releaseId()).orElse("");
    // The approval event exists before the ledger shows the action as waiting, so anyone who sees
    // it waiting finds the event to decide. The ledger applies the same policy, so this agrees with it.
    boolean halted = HaltEntity.isHalted(componentClient, run.orgId(), run.assistantId());
    var predicted = halted
        ? ActionPolicy.halted()
        : resolvedRisk.isPresent()
        ? ActionPolicy.decide(assistant.stage(), !releaseId.isBlank(), risk)
        : ActionPolicy.notAvailable();
    if (predicted.status() == ActionStatus.AWAITING_APPROVAL) raiseApproval(run, risk, predicted.reason());
    var recorded = componentClient.forEventSourcedEntity(ledgerId(run))
        .method(ActionLedgerEntity::request)
        .invoke(new ActionLedgerEntity.Request(run.orgId(), run.assistantId(), run.actionKey(),
            run.actionType(), run.payload(), risk, assistant.stage(), releaseId, resolvedRisk.isPresent(), halted));
    note(run, "requested " + run.actionType() + ": " + recorded.status() + " (" + recorded.reason() + ")");
    recordPolicyEvidence(run, recorded.status(), recorded.reason());

    return switch (recorded.status()) {
      case AWAITING_APPROVAL -> stepEffects()
          .updateState(run.withPhase(AWAITING_APPROVAL))
          .thenPause(pauseSetting(approvalWindow()).timeoutHandler(ActionWorkflow::approvalExpired));
      case APPROVED -> stepEffects()
          .updateState(run.withPhase("APPROVED"))
          .thenTransitionTo(ActionWorkflow::executeStep);
      default -> stepEffects().updateState(run.withPhase(recorded.status().name())).thenEnd();
    };
  }

  /** How long a person has to decide: the approval control's service level. */
  private static Duration approvalWindow() {
    return Duration.parse(ControlCatalog.current().require(Controls.ACTION_APPROVAL).human().slaTarget());
  }

  private static boolean pastApproval(String phase) {
    return List.of("APPROVING", "REJECTING", "APPROVED", "REJECTED", "SUCCEEDED", "FAILED", "OUTCOME_UNKNOWN")
        .contains(phase);
  }

  /** What the person is shown the action would send: personal data masked, and cut short. */
  private static String shownPayload(String payload) {
    var masked = PersonalDataMasker.mask(payload == null ? "" : payload).text();
    return masked.length() <= 300 ? masked : masked.substring(0, 299) + "…";
  }

  /** The human event an approval waits on. Raising it twice leaves the first, so a retried step is safe. */
  public static String humanEventId(String orgId, String assistantId, String actionKey) {
    return ScopedId.of(orgId, "he", "action", assistantId, actionKey);
  }

  private void raiseApproval(Run run, RiskClass risk, String why) {
    var raised = HumanEvents.raised(ControlCatalog.current(), Controls.ACTION_APPROVAL,
        humanEventId(run.orgId(), run.assistantId(), run.actionKey()), run.orgId(), "action",
        "Run " + run.actionType() + " for assistant " + run.assistantId(), run.assistantId(),
        Map.of("actionKey", run.actionKey(), "actionType", run.actionType(), "risk", risk.name(),
            "payload", shownPayload(run.payload())),
        "workflow", "risk=" + risk.name(), why,
        "Should assistant " + run.assistantId() + " be allowed to run " + run.actionType() + "?",
        List.of("The action is what the assistant meant to do.", "The payload is within what the assistant may change."),
        run.requestedBy(), System.currentTimeMillis());
    componentClient.forEventSourcedEntity(raised.id()).method(HumanEventEntity::raise).invoke(raised);
  }

  /**
   * The policy gate's decision as evidence. A shadow run is recorded as shadow, which proves
   * nothing; a request waiting for a person is the approval control's evidence, not this one's.
   */
  private void recordPolicyEvidence(Run run, ActionStatus status, String reason) {
    EvaluationMode mode = status == ActionStatus.WOULD_DO ? EvaluationMode.SHADOW : EvaluationMode.ENFORCING;
    String decision;
    String consequence;
    switch (status) {
      case DENIED -> { decision = "BLOCK"; consequence = "BLOCKED"; }
      case WOULD_DO -> { decision = "ALLOW"; consequence = "OBSERVED_ONLY"; }
      case APPROVED -> { decision = "ALLOW"; consequence = "CONTINUED"; }
      default -> { return; }
    }
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(run.orgId(), Controls.ACTION_POLICY,
        run.assistantId() + "~" + run.actionKey(), mode, decision, consequence, reason,
        run.actionType() + " " + run.actionKey(), "system:policy", "system", "", false, "", 0));
  }

  /** Tells the approval's human event that its decision has been carried out. Never stops the run. */
  private void settleEvent(Run run, String note) {
    try {
      componentClient.forEventSourcedEntity(humanEventId(run.orgId(), run.assistantId(), run.actionKey()))
          .method(HumanEventEntity::markApplied)
          .invoke(new HumanEventEntity.Applied(note, System.currentTimeMillis()));
    } catch (RuntimeException e) {
      // The ledger holds the outcome; the event only mirrors it for the queue.
    }
  }

  /**
   * A built-in action, or "packName.actionName" for an action offered by a connector pack that
   * is LIVE in this organization. Empty when the action is not available.
   */
  private Optional<RiskClass> resolveRisk(Run run) {
    var builtIn = ActionCatalog.riskOf(run.actionType());
    if (builtIn.isPresent()) return builtIn;
    int dot = run.actionType().indexOf('.');
    if (dot <= 0 || dot == run.actionType().length() - 1) return Optional.empty();
    var pack = componentClient
        .forEventSourcedEntity(ScopedId.of(run.orgId(), run.actionType().substring(0, dot)))
        .method(ConnectorPackEntity::get)
        .invoke();
    return pack.actionRisk(run.actionType().substring(dot + 1));
  }

  private StepEffect recordApprovalStep() {
    var run = currentState();
    // A halt that came after the request but before this approval still stops the action.
    if (HaltEntity.isHalted(componentClient, run.orgId(), run.assistantId())) {
      componentClient.forEventSourcedEntity(ledgerId(run)).method(ActionLedgerEntity::reject)
          .invoke(new ActionLedgerEntity.Reject("system", "assistant is halted"));
      note(run, run.actionType() + " was approved by " + run.decidedBy() + " but the assistant is halted; it did not run");
      settleEvent(run, "approved, but not run: the assistant is halted");
      return stepEffects().updateState(run.withPhase(ActionStatus.REJECTED.name())).thenEnd();
    }
    componentClient.forEventSourcedEntity(ledgerId(run)).method(ActionLedgerEntity::approve).invoke(new ActionLedgerEntity.Approve(run.decidedBy()));
    note(run, run.actionType() + " approved by " + run.decidedBy());
    settleEvent(run, "approved by " + run.decidedBy() + "; released to run");
    return stepEffects()
        .updateState(run.withPhase("APPROVED"))
        .thenTransitionTo(ActionWorkflow::executeStep);
  }

  private StepEffect recordRejectionStep() {
    var run = currentState();
    componentClient.forEventSourcedEntity(ledgerId(run))
        .method(ActionLedgerEntity::reject)
        .invoke(new ActionLedgerEntity.Reject(run.decidedBy(), run.rejectReason()));
    note(run, run.actionType() + " rejected by " + run.decidedBy() + ": " + run.rejectReason());
    settleEvent(run, "rejected by " + run.decidedBy());
    return stepEffects().updateState(run.withPhase(ActionStatus.REJECTED.name())).thenEnd();
  }

  private StepEffect executeStep() {
    var run = currentState();
    componentClient.forEventSourcedEntity(ledgerId(run)).method(ActionLedgerEntity::start).invoke();
    var call = packCall(run);
    var result = call.isPresent()
        ? executor.performPack(run.orgId(), call.get(), run.payload(), ledgerId(run))
        : executor.perform(run.orgId(), run.actionType(), run.payload());
    if (!result.succeeded() && result.detail().endsWith(SourceCaller.EGRESS_REFUSAL)) {
      new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(run.orgId(), Controls.EGRESS,
          "action~" + run.assistantId() + "~" + run.actionKey(), EvaluationMode.ENFORCING, "BLOCK", "BLOCKED",
          "host-not-allowed", run.actionType(), "system:egress", "system", "", false, "", 0));
    }
    if (result.succeeded()) {
      componentClient.forEventSourcedEntity(ledgerId(run)).method(ActionLedgerEntity::succeed).invoke(new ActionLedgerEntity.Finish(result.detail()));
      note(run, run.actionType() + " succeeded");
      return stepEffects().updateState(run.withPhase(ActionStatus.SUCCEEDED.name())).thenEnd();
    }
    componentClient.forEventSourcedEntity(ledgerId(run)).method(ActionLedgerEntity::fail).invoke(new ActionLedgerEntity.Finish(result.detail()));
    note(run, run.actionType() + " failed: " + result.detail());
    return stepEffects().updateState(run.withPhase(ActionStatus.FAILED.name())).thenEnd();
  }

  /** The pack action a "pack.action" type names, when the pack is live and offers it. */
  private Optional<ActionExecutor.PackCall> packCall(Run run) {
    int dot = run.actionType().indexOf('.');
    if (dot <= 0 || ActionCatalog.riskOf(run.actionType()).isPresent()) return Optional.empty();
    var pack = componentClient.forEventSourcedEntity(ScopedId.of(run.orgId(), run.actionType().substring(0, dot)))
        .method(ConnectorPackEntity::get).invoke();
    var name = run.actionType().substring(dot + 1);
    return pack.active()
        .filter(v -> pack.mode() == io.akka.contextlayer.domain.ConnectorPack.Mode.LIVE)
        .flatMap(v -> v.definition().actions() == null ? Optional.empty() : v.definition().actions().stream()
            .filter(a -> a.name().equals(name)).findFirst()
            .map(a -> new ActionExecutor.PackCall(v.definition(), a)));
  }

  private StepEffect unknownOutcomeStep() {
    var run = currentState();
    componentClient.forEventSourcedEntity(ledgerId(run))
        .method(ActionLedgerEntity::markOutcomeUnknown)
        .invoke(new ActionLedgerEntity.Finish("the call did not complete; a person must check the target system"));
    note(run, run.actionType() + " has an unknown outcome; a person must check the target system");
    return stepEffects().updateState(run.withPhase(ActionStatus.OUTCOME_UNKNOWN.name())).thenEnd();
  }

  private StepEffect expireStep() {
    var run = currentState();
    // A reviewer may have given the event more time; wait out what is left rather than reject early.
    var event = componentClient.forEventSourcedEntity(humanEventId(run.orgId(), run.assistantId(), run.actionKey()))
        .method(HumanEventEntity::get).invoke();
    long remaining = event.dueAt() - System.currentTimeMillis();
    if (event.exists() && event.pending() && remaining > 0) {
      return stepEffects().updateState(run)
          .thenPause(pauseSetting(Duration.ofMillis(remaining + 1000)).timeoutHandler(ActionWorkflow::approvalExpired));
    }
    // The event times out under its own rules; if it was decided just before the window closed it
    // refuses, and the run still ends here because the ledger below only accepts one outcome.
    componentClient.forEventSourcedEntity(humanEventId(run.orgId(), run.assistantId(), run.actionKey()))
        .method(HumanEventEntity::expire)
        .invoke(new HumanEventEntity.Expire(System.currentTimeMillis()));
    componentClient.forEventSourcedEntity(ledgerId(run))
        .method(ActionLedgerEntity::reject)
        .invoke(new ActionLedgerEntity.Reject("system", "approval expired"));
    note(run, run.actionType() + " expired without approval");
    return stepEffects().updateState(run.withPhase(ActionStatus.REJECTED.name())).thenEnd();
  }

  /** Runs when nobody approved or rejected within the pause window. */
  private Effect<Done> approvalExpired() {
    return effects().transitionTo(ActionWorkflow::expireStep).thenReply(Done.getInstance());
  }

  /**
   * Adds a line to the timeline of the case this action belongs to. The timeline is a record for
   * people, so a case that is closed or missing never stops the action itself.
   */
  private void note(Run run, String text) {
    if (run.caseId() == null || run.caseId().isBlank()) return;
    try {
      componentClient.forEventSourcedEntity(run.caseId()).method(CaseEntity::annotate)
          .invoke(new CaseEntity.Annotate("assistant " + run.assistantId(), text, System.currentTimeMillis()));
    } catch (RuntimeException e) {
      // intentionally ignored: see above
    }
  }

  private String ledgerId(Run run) {
    return ScopedId.of(run.orgId(), run.assistantId(), run.actionKey());
  }
}

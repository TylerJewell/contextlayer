package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.HumanDecision;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.HumanEventEvent;

/**
 * Derives evidence from what happened to human events. Evidence comes from the event journal, so a
 * decision cannot be made without it being recorded, and replaying the journal records the same
 * facts under the same ids.
 *
 * <p>Each vote is evidence for the event's own control. Where the control measures review time,
 * each vote is also evidence for the rushed-decision control: a pass when the person took at least
 * the minimum, a fail when they did not. A rehearsal is recorded in test mode, which proves nothing.
 */
@Component(id = "human-evidence-consumer")
@Consume.FromEventSourcedEntity(HumanEventEntity.class)
public class HumanEvidenceConsumer extends Consumer {

  private final ComponentClient componentClient;

  public HumanEvidenceConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(HumanEventEvent event) {
    switch (event) {
      case HumanEventEvent.Decided e -> vote(e.decision(), EvaluationMode.ENFORCING, "vote");
      case HumanEventEvent.Rehearsed e -> vote(e.decision(), EvaluationMode.TEST, "rehearsal");
      case HumanEventEvent.TimedOut e -> timedOut(e);
      case HumanEventEvent.Withdrawn e -> withdrawn(e);
      default -> { }
    }
    return effects().done();
  }

  private HumanEvent current() {
    var id = messageContext().eventSubject().orElseThrow();
    return componentClient.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke();
  }

  private void vote(HumanDecision decision, EvaluationMode mode, String kind) {
    var event = current();
    var recorder = new EvidenceRecorder(componentClient);
    var reviewer = decision.reviewer().subject();
    var part = event.id() + "~" + kind + "~" + reviewer + "~" + decision.submittedAt();
    var consequence = "APPROVE".equals(decision.action()) ? "CONTINUED" : "BLOCKED";
    recorder.record(new EvidenceRecorder.Fact(event.orgId(), event.controlId(), part, mode, decision.action(),
        consequence, decision.belowMinimum() ? "below-minimum-review-time" : "reviewed", event.id(), reviewer,
        decision.authority(), event.id(), decision.belowMinimum(), "", decision.submittedAt()));

    if (event.policy().minimumReviewSeconds() > 0) {
      recorder.record(new EvidenceRecorder.Fact(event.orgId(), Controls.RUSHED_DECISIONS, part, mode,
          decision.belowMinimum() ? "FAIL" : "PASS", "CONTINUED",
          decision.belowMinimum() ? "below-minimum-review-time" : "minimum-review-time-kept", event.id(), reviewer,
          decision.authority(), event.id(), decision.belowMinimum(), "", decision.submittedAt()));
    }
  }

  /** A withdrawn event closes without a decision; an action's approval withdrawn by its requester denies the action. */
  private void withdrawn(HumanEventEvent.Withdrawn withdrawn) {
    var event = current();
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(event.orgId(), event.controlId(),
        event.id() + "~withdrawn", EvaluationMode.ENFORCING, "WITHDRAW", "BLOCKED", "withdrawn by the proposer",
        event.id(), withdrawn.reviewer().subject(), withdrawn.reviewer().authority(), event.id(), false,
        "HumanGateWithdrawn", withdrawn.at()));
  }

  private void timedOut(HumanEventEvent.TimedOut timedOut) {
    var event = current();
    var blocked = "DENY".equals(timedOut.outcomeAction());
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(event.orgId(), event.controlId(),
        event.id() + "~timeout", EvaluationMode.ENFORCING, "TIMEOUT", blocked ? "BLOCKED" : "OBSERVED_ONLY",
        timedOut.slaOutcome().toLowerCase(), event.id(), "system", "system", event.id(), false, "HumanGateTimedOut",
        timedOut.at()));
  }
}

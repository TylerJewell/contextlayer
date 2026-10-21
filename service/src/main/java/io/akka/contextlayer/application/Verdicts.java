package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.Evidence;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.Refusal;
import java.util.UUID;

/**
 * Records one verdict of a control in the vocabulary of its mechanism, so every enforcement point writes the same
 * shape: a guardrail passes or blocks, a sanitizer masks or finds nothing to mask, an eval passes or finds a problem,
 * a gate passes or holds the release back. A refusal is recorded like a pass; both are the control working.
 */
public final class Verdicts {

  private final EvidenceRecorder recorder;

  public Verdicts(ComponentClient componentClient) {
    this(new EvidenceRecorder(componentClient));
  }

  public Verdicts(EvidenceRecorder recorder) {
    this.recorder = recorder;
  }

  /** An id part for a fact that has no natural key, such as one refused request: every call is its own record. */
  public static String once(String subject) {
    return subject + "~" + UUID.randomUUID();
  }

  public Evidence guardrail(
      String orgId, String controlId, String idPart, boolean pass, String reason, String subject, String actor) {
    return guardrail(orgId, controlId, idPart, EvaluationMode.ENFORCING, pass, reason, subject, actor);
  }

  public Evidence guardrail(
      String orgId, String controlId, String idPart, EvaluationMode mode, boolean pass, String reason, String subject,
      String actor) {
    return record(orgId, controlId, idPart, mode, pass ? "PASS" : "BLOCK", pass ? "CONTINUED" : "BLOCKED", reason,
        subject, actor);
  }

  /** @param masked how many values were masked; none means the text passed through unchanged */
  public Evidence sanitizer(String orgId, String controlId, String idPart, int masked, String subject, String actor) {
    return record(orgId, controlId, idPart, EvaluationMode.ENFORCING, "PASS", masked > 0 ? "MASKED" : "CONTINUED",
        masked > 0 ? "masked-" + masked : "nothing-to-mask", subject, actor);
  }

  /** A finding is a failed check: it breaches the control until it ages out of the compliance window. */
  public Evidence eval(String orgId, String controlId, String idPart, boolean pass, String reason, String subject) {
    return record(orgId, controlId, idPart, EvaluationMode.ENFORCING, pass ? "PASS" : "FAIL",
        pass ? "CONTINUED" : "OBSERVED_ONLY", reason, subject, "system:" + controlId);
  }

  /** A check that did not apply, such as a bundle gate before any bundle is signed: it neither upholds nor breaches. */
  public Evidence notApplicable(String orgId, String controlId, String idPart, String reason, String subject) {
    return record(orgId, controlId, idPart, EvaluationMode.ENFORCING, "SKIPPED", "OBSERVED_ONLY", reason, subject,
        "system:" + controlId);
  }

  public Evidence gate(String orgId, String controlId, String idPart, boolean pass, String reason, String subject) {
    return record(orgId, controlId, idPart, EvaluationMode.ENFORCING, pass ? "PASS" : "FAIL",
        pass ? "CONTINUED" : "BLOCKED", reason, subject, "system:" + controlId);
  }

  /**
   * A request on a human event that its control's rules turned down: the wrong role, the proposer deciding their own
   * event, no rationale, past its deadline. The event is unchanged, so this record is the only trace of the attempt.
   */
  public Evidence humanRefusal(HumanEvent event, String actor, String verb, Refusal refusal, boolean rehearsal) {
    return recorder.record(new EvidenceRecorder.Fact(event.orgId(), event.controlId(), once(event.id() + "~" + verb),
        rehearsal ? EvaluationMode.TEST : EvaluationMode.ENFORCING, "REFUSED", "BLOCKED",
        refusal.kind().name().toLowerCase() + ": " + refusal.message(), event.id(), actor, "", event.id(), false,
        HUMAN_REFUSED, 0));
  }

  /**
   * A request turned down by a human control's rule before any event was raised for it, such as someone other than
   * a playbook's owner approving its release.
   */
  public Evidence humanRefusal(String orgId, String controlId, String subject, String actor, String reason) {
    return recorder.record(new EvidenceRecorder.Fact(orgId, controlId, once(subject), EvaluationMode.ENFORCING,
        "REFUSED", "BLOCKED", reason, subject, actor, "", "", false, HUMAN_REFUSED, 0));
  }

  /** The event type of a refused request on a human event; the control's proof event is for decisions. */
  public static final String HUMAN_REFUSED = "HumanRequestRefused";

  private Evidence record(
      String orgId, String controlId, String idPart, EvaluationMode mode, String decision, String consequence,
      String reason, String subject, String actor) {
    return recorder.record(EvidenceRecorder.fact(orgId, controlId, idPart, mode, decision, consequence, reason,
        subject, actor));
  }
}

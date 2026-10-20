package io.akka.contextlayer.governance;

/**
 * One record that a control ran, in the shape of the governance event envelope: which control
 * (the only key joining evidence to the catalog), where, how (enforcing or not), what it decided
 * and what followed from that, who acted, and which catalog version was in force.
 *
 * @param evidenceId deterministic for what it records, so recording the same fact twice records it once
 * @param eventType one of the control's proof events, such as HumanDecisionRecorded
 * @param boundary where the control sat, such as WORKFLOW_STEP
 * @param decision PASS, BLOCK, ERROR, ALLOW, DENY, FAIL or the human action taken
 * @param consequence CONTINUED, BLOCKED, FAILED_CLOSED, FAILED_OPEN, MASKED, REDACTED, FALLBACK_USED or OBSERVED_ONLY
 * @param subject what the control acted on, such as an action key
 * @param actor the person or part of the system that acted
 * @param authority for a person, the identity provider and roles they acted under
 * @param ref the human event this came from, when there is one
 * @param flagged the record carries a warning, such as a decision made below the minimum review time
 */
public record Evidence(
    String evidenceId,
    String orgId,
    String controlId,
    String eventType,
    String boundary,
    EvaluationMode mode,
    String decision,
    String consequence,
    String reasonCode,
    String subject,
    String actor,
    String authority,
    String bundleDigest,
    String ref,
    boolean flagged,
    long at) {

  /**
   * What this record says about the control. Only enforcing records can uphold or breach it; a
   * control that failed open, or whose evaluation failed, is not upheld; a record that merely
   * observed proves nothing either way.
   */
  public Verdict verdict() {
    if (mode != EvaluationMode.ENFORCING) return Verdict.INCONCLUSIVE;
    if ("ERROR".equals(decision)) return Verdict.ERROR;
    if ("FAILED_OPEN".equals(consequence) || "FAIL".equals(decision)) return Verdict.BREACHED;
    if ("OBSERVED_ONLY".equals(consequence)) return Verdict.INCONCLUSIVE;
    return Verdict.UPHELD;
  }
}

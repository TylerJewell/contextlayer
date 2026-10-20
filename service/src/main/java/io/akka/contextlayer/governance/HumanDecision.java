package io.akka.contextlayer.governance;

import java.util.List;

/**
 * One person's decision on an event, as it was made.
 *
 * @param presentedAt when the person was shown the event; the minimum review time counts from it
 * @param reviewSeconds time between being shown the event and submitting
 * @param belowMinimum submitted before the control's minimum review time
 * @param authority the identity provider and roles the decision was made under
 */
public record HumanDecision(
    Reviewer reviewer,
    String action,
    String rationale,
    boolean independent,
    List<String> checks,
    long presentedAt,
    long submittedAt,
    long reviewSeconds,
    boolean belowMinimum,
    String authority) {

  public HumanDecision {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }
}

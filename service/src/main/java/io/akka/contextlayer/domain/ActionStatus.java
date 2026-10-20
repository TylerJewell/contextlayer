package io.akka.contextlayer.domain;

public enum ActionStatus {
  /** Shadow mode: recorded as what the assistant would have done; nothing was sent. */
  WOULD_DO,
  /** Refused by policy (draft assistant, action never allowed, or not in the catalog). */
  DENIED,
  AWAITING_APPROVAL,
  /** Cleared to run: unattended action, or approved by a person. */
  APPROVED,
  REJECTED,
  IN_FLIGHT,
  SUCCEEDED,
  FAILED,
  /** The call may or may not have taken effect. Never retried automatically. */
  OUTCOME_UNKNOWN;

  public boolean isTerminal() {
    return this == WOULD_DO || this == DENIED || this == REJECTED || this == SUCCEEDED
        || this == FAILED;
  }
}

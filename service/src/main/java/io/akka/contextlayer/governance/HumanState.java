package io.akka.contextlayer.governance;

/** Where a human event is in its life. The names follow the human events contract. */
public enum HumanState {
  OPEN(true),
  CLAIMED(true),
  AWAITING_SECOND(true),
  ESCALATED(true),
  CHANGES_REQUESTED(true),
  REOPENED(true),
  DECIDED(false),
  APPLIED(false),
  TIMED_OUT(false),
  WITHDRAWN(false);

  private final boolean pending;

  HumanState(boolean pending) {
    this.pending = pending;
  }

  /** Whether a person still has something to do. */
  public boolean pending() {
    return pending;
  }
}

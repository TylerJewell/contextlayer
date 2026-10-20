package io.akka.contextlayer.governance;

/** What one piece of evidence says about its control. */
public enum Verdict {
  UPHELD,
  BREACHED,
  INCONCLUSIVE,
  ERROR
}

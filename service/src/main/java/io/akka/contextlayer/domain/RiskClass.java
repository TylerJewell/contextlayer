package io.akka.contextlayer.domain;

/** How an action may be taken by an assistant that is Live. */
public enum RiskClass {
  UNATTENDED,
  NEEDS_APPROVAL,
  NEVER_BY_ASSISTANT
}

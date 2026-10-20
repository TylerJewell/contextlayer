package io.akka.contextlayer.governance;

/** The five ways a control acts, as the governance model names them. */
public enum Mechanism {
  GUARDRAIL,
  HUMAN,
  SANITIZER,
  EVAL,
  GATE
}

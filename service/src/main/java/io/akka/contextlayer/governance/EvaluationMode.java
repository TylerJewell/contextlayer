package io.akka.contextlayer.governance;

/** Shadow, advisory and test evaluations are recorded but never count as proof that something was protected. */
public enum EvaluationMode {
  ENFORCING,
  SHADOW,
  ADVISORY,
  TEST
}

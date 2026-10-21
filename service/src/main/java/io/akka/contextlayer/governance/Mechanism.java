package io.akka.contextlayer.governance;

import java.util.List;

/**
 * The five ways a control acts, as Verify names them, with each one's flavors (its sub-types) and the one event every
 * verdict of its controls is recorded as.
 */
public enum Mechanism {
  GUARDRAIL("GuardrailDecisionRecorded",
      List.of("guardrail-tool-permission", "guardrail-llm-content-evaluation")),
  HUMAN("HumanDecisionRecorded",
      List.of("human-domain-decision", "human-system-authorization", "human-monitoring", "human-feedback", "human-halt")),
  SANITIZER("SanitizerApplied",
      List.of("sanitizer-pii", "sanitizer-special-category", "sanitizer-secret", "sanitizer-sector", "sanitizer-child")),
  EVAL("EvaluationRecorded",
      List.of("eval-drift-fairness-watch", "eval-performance-monitor", "eval-on-incident-reporter", "eval-on-decision-eval",
          "eval-on-change-revalidation", "eval-on-release")),
  GATE("GateEvaluated",
      List.of("gate-documentation", "gate-attestation", "gate-test", "gate-configuration"));

  /** The six points in an agent's lifecycle at which a guardrail fires. */
  public static final List<String> HOOKS = List.of("before-agent-invocation", "before-agent-response", "before-llm-call",
      "after-llm-response", "before-tool-call", "after-tool-call");

  /** Where a sanitizer is applied. */
  public static final List<String> INJECTION_POINTS = List.of("interaction-log", "before-tool-call", "before-llm-call",
      "before-agent-response");

  /** When a gate runs: before release, or once inside the release when it first starts. */
  public static final List<String> GATE_RUNS = List.of("pre-release", "at-deployment");

  private final String proofEvent;
  private final List<String> flavors;

  Mechanism(String proofEvent, List<String> flavors) {
    this.proofEvent = proofEvent;
    this.flavors = flavors;
  }

  public String proofEvent() {
    return proofEvent;
  }

  public List<String> flavors() {
    return flavors;
  }

  /** The boundary a guardrail's evidence names, from its hook: {@code before-tool-call} is {@code BEFORE_TOOL_CALL}. */
  public static String boundaryOf(String hook) {
    return hook.toUpperCase().replace('-', '_');
  }
}

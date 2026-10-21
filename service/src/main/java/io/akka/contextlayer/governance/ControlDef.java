package io.akka.contextlayer.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One control of the catalog.
 *
 * @param rowId stable identifier; the only key joining a control to its evidence
 * @param flavor the mechanism's sub-type, as Verify names it, such as {@code guardrail-tool-permission}
 * @param hook for a guardrail, the point at which it fires, such as {@code before-tool-call}
 * @param proofEvent the event type whose presence shows the control ran
 * @param human present only when the mechanism is {@link Mechanism#HUMAN}
 */
public record ControlDef(
    String rowId,
    String name,
    Mechanism mechanism,
    String flavor,
    String hook,
    String lifecycle,
    String corpusRef,
    String summary,
    String proofEvent,
    String boundary,
    List<String> appliesTo,
    HumanPolicy humanConfig,
    SanitizerConfig sanitizerConfig,
    EvalConfig evalConfig,
    GateConfig gateConfig,
    @JsonProperty("implements") List<String> obligations) {

  /** What a sanitizer covers and where it is applied. */
  public record SanitizerConfig(List<String> dataClasses, String injectionPoint) {
    public SanitizerConfig {
      dataClasses = dataClasses == null ? List.of() : List.copyOf(dataClasses);
    }
  }

  /** What an eval checks, what happens on a finding, and what makes it run. */
  public record EvalConfig(String triggerEvent, String frequency, String checkIntent, String onFinding) {}

  /**
   * What a gate checks and what it holds back.
   *
   * @param runsAt {@code pre-release} (in a build or pipeline) and {@code at-deployment} (once, inside the release,
   *     when it first starts)
   */
  public record GateConfig(
      String documentKind,
      String artifactPattern,
      String checkIntent,
      String freshnessWindow,
      String failsWhen,
      String blocks,
      List<String> runsAt) {
    public GateConfig {
      runsAt = runsAt == null ? List.of() : List.copyOf(runsAt);
    }
  }

  /**
   * {@code corpusRef} is the one corpus control the row is, for a row taken from the corpus; an officer row has none
   * and lists the corpus controls it meets under {@code implements}, the first of which stands in for it.
   */
  public ControlDef {
    appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
    obligations = obligations == null ? List.of() : List.copyOf(obligations);
    if ((corpusRef == null || corpusRef.isBlank()) && !obligations.isEmpty()) corpusRef = obligations.get(0);
  }

  public HumanPolicy human() {
    return humanConfig;
  }
}

package io.akka.contextlayer.governance;

import java.util.List;

/**
 * One control of the catalog.
 *
 * @param rowId stable identifier; the only key joining a control to its evidence
 * @param proofEvent the event type whose presence shows the control ran
 * @param human present only when the mechanism is {@link Mechanism#HUMAN}
 */
public record ControlDef(
    String rowId,
    String name,
    Mechanism mechanism,
    String lifecycle,
    String corpusRef,
    String summary,
    String proofEvent,
    String boundary,
    List<String> appliesTo,
    HumanPolicy humanConfig) {

  public ControlDef {
    appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
  }

  public HumanPolicy human() {
    return humanConfig;
  }
}

package io.akka.contextlayer.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    HumanPolicy humanConfig,
    @JsonProperty("implements") List<String> obligations) {

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

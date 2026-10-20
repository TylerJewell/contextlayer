package io.akka.contextlayer.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The actions an assistant may ever request, with their risk class (REQUIREMENTS AST-04). An
 * action type that is not listed does not exist as far as assistants are concerned.
 *
 * <p>Connector packs will declare these per source; this fixed catalog covers the
 * built-in actions.
 */
public final class ActionCatalog {

  private static final Map<String, RiskClass> BUILT_IN = Map.of(
      "notify.log", RiskClass.UNATTENDED,
      "record.update", RiskClass.NEEDS_APPROVAL,
      "payment.release", RiskClass.NEVER_BY_ASSISTANT);

  private ActionCatalog() {}

  public static Optional<RiskClass> riskOf(String actionType) {
    return Optional.ofNullable(BUILT_IN.get(actionType));
  }
}

package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.governance.EvaluationMode;

/**
 * Copies an evaluator's verdict on one interaction into the project's evidence. The runtime keeps the verdict in its
 * own ledger under the evaluator's control id, which the service cannot write to or list by project; this record puts
 * the eval beside the project's other controls, pointing back at the ledger record ({@code ledger:<interaction id>}).
 * Every agent session the service opens is named under its project, so the project is the session id's first part.
 */
final class LedgerVerdicts {
  private LedgerVerdicts() {}

  static void record(
      ComponentClient componentClient, String controlId, String sessionId, String interactionId, Boolean passed,
      String explanation) {
    int colon = sessionId == null ? -1 : sessionId.indexOf(':');
    if (colon <= 0) return;
    var project = sessionId.substring(0, colon);
    String decision;
    String consequence;
    if (passed == null) {
      decision = "SKIPPED";
      consequence = "OBSERVED_ONLY";
    } else {
      decision = passed ? "PASS" : "FAIL";
      consequence = passed ? "CONTINUED" : "OBSERVED_ONLY";
    }
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(project, controlId, interactionId,
        EvaluationMode.ENFORCING, decision, consequence, explanation, "interaction " + interactionId,
        "system:" + controlId, "", "ledger:" + interactionId, false, "", 0));
  }
}

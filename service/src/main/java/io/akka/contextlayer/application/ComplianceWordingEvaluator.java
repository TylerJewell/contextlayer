package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.evaluation.EvaluationContext;
import akka.javasdk.evaluation.Evaluator;
import akka.javasdk.ledger.LedgerClient;
import io.akka.contextlayer.governance.ComplianceAnswer;

/**
 * Judges each compliance-summary interaction from the runtime's ledger: does the wording claim only what the facts
 * it was given say (control custom-cl0016)? {@link ComplianceAnswerer} applies the same check before an answer is
 * shown and falls back to the plain facts; this records the runtime's verdict on what the model wrote.
 */
@Component(id = "compliance-wording-evaluator")
public class ComplianceWordingEvaluator extends Evaluator {

  private final LedgerClient ledger;

  public ComplianceWordingEvaluator(LedgerClient ledger) {
    this.ledger = ledger;
  }

  @Override
  public Effect evaluate(EvaluationContext context) {
    var interaction = ledger.getInteraction(context.subject().interactionId());
    if (interaction.failed()) return effects().inconclusive("the interaction failed, so nothing was worded");
    var message = interaction.systemMessage();
    int start = message.indexOf(ComplianceSummaryAgent.FACTS_START);
    int end = start < 0 ? -1 : message.indexOf(ComplianceSummaryAgent.FACTS_END, start);
    if (end < 0) return effects().inconclusive("the system message holds no facts");
    var facts = message.substring(start + ComplianceSummaryAgent.FACTS_START.length(), end);
    var wording = interaction.finalResponseText();
    if (wording == null || wording.isBlank()) return effects().complete(Evaluation.failed("no answer was given").withLabel("empty"));

    var check = ComplianceAnswer.check(wording, facts);
    return effects().complete(check.ok()
        ? Evaluation.passed("every control, number and claim in the answer is in the facts").withLabel("supported")
        : Evaluation.failed("the answer states what the facts do not: " + String.join(", ", check.unsupported()))
            .withLabel("unsupported-claim"));
  }
}

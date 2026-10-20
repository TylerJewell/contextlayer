package io.akka.contextlayer.application;

import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Component;
import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.evaluation.EvaluationContext;
import akka.javasdk.evaluation.Evaluator;
import akka.javasdk.ledger.LedgerClient;
import java.util.List;

/**
 * Judges each classifier interaction from the runtime's ledger: was the label the model answered with one of the
 * labels it was offered (control custom-cl0002)? The playbook refuses a label outside the set before acting on it;
 * this is the runtime's own record of the same rule, read from what the model was actually sent and what it said.
 */
@Component(id = "label-allowed-evaluator")
public class LabelAllowedEvaluator extends Evaluator {

  private final LedgerClient ledger;

  public LabelAllowedEvaluator(LedgerClient ledger) {
    this.ledger = ledger;
  }

  @Override
  public Effect evaluate(EvaluationContext context) {
    var interaction = ledger.getInteraction(context.subject().interactionId());
    if (interaction.failed()) return effects().inconclusive("the interaction failed, so no label was given");
    var message = interaction.systemMessage();
    int start = message.indexOf(ClassifierAgent.LABELS_START);
    int end = start < 0 ? -1 : message.indexOf(ClassifierAgent.LABELS_END, start);
    if (end < 0) return effects().inconclusive("the system message lists no allowed labels");
    var allowed = List.of(message.substring(start + ClassifierAgent.LABELS_START.length(), end).split(", "));

    String label;
    try {
      label = JsonSupport.getObjectMapper().readTree(interaction.finalResponseText()).path("label").asText("");
    } catch (Exception e) {
      return effects().complete(Evaluation.failed("the answer is not a judgment: " + e.getMessage())
          .withLabel("not-a-judgment"));
    }
    return effects().complete(allowed.contains(label)
        ? Evaluation.passed("answered " + label + ", one of " + String.join(", ", allowed)).withLabel("label-allowed")
        : Evaluation.failed("answered " + (label.isEmpty() ? "no label" : label) + ", not one of " + String.join(", ", allowed))
            .withLabel("label-not-allowed"));
  }
}

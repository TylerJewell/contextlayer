package io.akka.contextlayer.application;

import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.evaluation.EvaluationContext;
import akka.javasdk.evaluation.Evaluator;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.contextlayer.governance.Controls;
import java.util.List;

/**
 * Judges each classifier interaction from the runtime's ledger: was the label the model answered with one of the
 * labels it was offered (control custom-cl0050)? The playbook refuses a label outside the set before acting on it
 * (custom-cl0002); this is the runtime's own record of the same rule, read from what the model was actually sent and
 * what it said, and it is copied into the project's evidence.
 */
@Component(id = "label-allowed-evaluator")
public class LabelAllowedEvaluator extends Evaluator {

  private final LedgerClient ledger;
  private final ComponentClient componentClient;

  public LabelAllowedEvaluator(LedgerClient ledger, ComponentClient componentClient) {
    this.ledger = ledger;
    this.componentClient = componentClient;
  }

  @Override
  public Effect evaluate(EvaluationContext context) {
    var interaction = ledger.getInteraction(context.subject().interactionId());
    if (interaction.failed()) return inconclusive(interaction, "the interaction failed, so no label was given");
    var message = interaction.systemMessage();
    int start = message.indexOf(ClassifierAgent.LABELS_START);
    int end = start < 0 ? -1 : message.indexOf(ClassifierAgent.LABELS_END, start);
    if (end < 0) return inconclusive(interaction, "the system message lists no allowed labels");
    var allowed = List.of(message.substring(start + ClassifierAgent.LABELS_START.length(), end).split(", "));

    String label;
    try {
      label = JsonSupport.getObjectMapper().readTree(interaction.finalResponseText()).path("label").asText("");
    } catch (Exception e) {
      return complete(interaction, Evaluation.failed("the answer is not a judgment: " + e.getMessage())
          .withLabel("not-a-judgment"));
    }
    return complete(interaction, allowed.contains(label)
        ? Evaluation.passed("answered " + label + ", one of " + String.join(", ", allowed)).withLabel("label-allowed")
        : Evaluation.failed("answered " + (label.isEmpty() ? "no label" : label) + ", not one of " + String.join(", ", allowed))
            .withLabel("label-not-allowed"));
  }

  private Effect complete(InteractionRecord interaction, Evaluation evaluation) {
    LedgerVerdicts.record(componentClient, Controls.LABEL_AUDIT, interaction.sessionId(), interaction.interactionId(),
        evaluation.passed(), evaluation.explanation());
    return effects().complete(evaluation);
  }

  private Effect inconclusive(InteractionRecord interaction, String reason) {
    LedgerVerdicts.record(componentClient, Controls.LABEL_AUDIT, interaction.sessionId(), interaction.interactionId(), null, reason);
    return effects().inconclusive(reason);
  }
}

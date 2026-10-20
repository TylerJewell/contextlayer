package io.akka.contextlayer.application;

import akka.javasdk.agent.Decision;
import akka.javasdk.agent.ModelCallGuardrail;

/**
 * Refuses a call to the question-drafting model when what it would be shown is more than the shape of the data
 * (control custom-cl0013). It reads the block of the system message that {@link QuestionAgent} reserves for the
 * shape and applies the same rules as {@link QuestionDrafter#describe}, so a record that reached the prompt by any
 * route stops at the model boundary.
 */
public class QuestionShapeGuardrail implements ModelCallGuardrail {

  @Override
  public Decision decide(CallContext ctx) {
    var message = ctx.systemMessage();
    int start = message.indexOf(QuestionAgent.SHAPE_START);
    int end = start < 0 ? -1 : message.indexOf(QuestionAgent.SHAPE_END, start);
    if (end < 0) return new Decision.Deny("the system message has no data shape block");
    var problems = QuestionDrafter.shapeProblems(message.substring(start + QuestionAgent.SHAPE_START.length(), end));
    return problems.isEmpty() ? new Decision.Allow("shape only") : new Decision.Deny(String.join("; ", problems));
  }
}

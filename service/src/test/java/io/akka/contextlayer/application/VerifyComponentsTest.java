package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.Tracing;
import akka.javasdk.agent.Decision;
import akka.javasdk.agent.MessageContent.TextMessageContent;
import akka.javasdk.agent.ModelCallGuardrail;
import akka.javasdk.evaluation.Subject;
import akka.javasdk.ledger.InteractionMetadata;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.testkit.EvaluatorTestKit;
import akka.javasdk.testkit.TestLedgerClient;
import io.akka.contextlayer.application.EntityStore.FieldSchema;
import io.akka.contextlayer.application.EntityStore.TypeSchema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The guardrail and evaluators the runtime runs for the controls, driven with what the agents really send. */
public class VerifyComponentsTest {

  private static final List<TypeSchema> SCHEMA = List.of(new TypeSchema("Claim", 500, List.of(
      new FieldSchema("claimId", "string", 200, 200, List.of("CL-1")),
      new FieldSchema("status", "string", 2, 200, List.of("OPEN", "CLOSED")),
      new FieldSchema("daysOpen", "number", 60, 200, List.of()))));

  private static Decision shapeDecision(String shown) {
    var message = QuestionAgent.systemMessage(new QuestionAgent.Request("open claims", shown, ""));
    return new QuestionShapeGuardrail().decide(new ModelCallGuardrail.CallContext() {
      public String agentId() { return "question-agent"; }
      public String sessionId() { return "s"; }
      public String modelName() { return "m"; }
      public String systemMessage() { return message; }
      public List<akka.javasdk.agent.Guardrail.Message> messages() { return List.of(); }
      public List<akka.javasdk.agent.Guardrail.Message> newMessages() { return List.of(); }
      public Tracing tracing() { return null; }
    });
  }

  @Test
  public void theQuestionModelMayBeShownTheShapeTheDrafterWrites() {
    assertThat(shapeDecision(QuestionDrafter.describe(SCHEMA))).isInstanceOf(Decision.Allow.class);
  }

  @Test
  public void aRecordValueInTheShapeIsRefusedAtTheModelBoundary() {
    var leaked = QuestionDrafter.describe(SCHEMA) + "  owner: string, one of jane.doe@example.com\n";
    assertThat(shapeDecision(leaked)).isInstanceOfSatisfying(Decision.Deny.class,
        d -> assertThat(d.reason()).contains("not a category"));

    var record = QuestionDrafter.describe(SCHEMA) + "CL-1 Jane Doe OPEN 42\n";
    assertThat(shapeDecision(record)).isInstanceOf(Decision.Deny.class);
  }

  /** Session "s-1" names no project, so the evaluators copy nothing into a project's evidence and need no client. */
  private static InteractionRecord interaction(String agent, String systemMessage, String reply) {
    return new InteractionRecord("i-1", "s-1", agent, Optional.empty(),
        new InteractionMetadata(null, java.util.Map.of(), Instant.EPOCH, Instant.EPOCH, null),
        systemMessage, List.of(new TextMessageContent("input")),
        List.of(new ModelResponse("r-1", reply, 10, 5, "", List.of())), List.of(), Optional.empty(), Optional.empty(),
        Instant.EPOCH);
  }

  private static akka.javasdk.evaluation.Evaluation labelVerdict(String reply) {
    var message = ClassifierAgent.systemMessage(new ClassifierAgent.Request("Label the claim.", "text", List.of("MINOR", "MAJOR")));
    var ledger = TestLedgerClient.create().seed(interaction("classifier-agent", message, reply));
    var result = EvaluatorTestKit.of(() -> new LabelAllowedEvaluator(ledger, null))
        .evaluate(new Subject.Interaction("i-1", "classifier-agent", Optional.empty()));
    assertThat(result.isComplete()).isTrue();
    return result.getEvaluation();
  }

  @Test
  public void theLedgerShowsWhetherTheClassifierAnsweredWithAnOfferedLabel() {
    assertThat(labelVerdict("{\"label\":\"MAJOR\",\"reason\":\"r\"}").passed()).isTrue();
    var outside = labelVerdict("{\"label\":\"CATASTROPHIC\",\"reason\":\"r\"}");
    assertThat(outside.passed()).isFalse();
    assertThat(outside.label()).contains("label-not-allowed");
  }

  private static akka.javasdk.evaluation.Evaluation wordingVerdict(String reply) {
    var message = """
        You write a short, plain answer for an auditor, using only the facts below.

        Facts:
        Control custom-cl0003 has 4 decisions in 90 days.

        What the records do not show:
        Whether the reviewers read the payload.

        Rules:
        - Use only what the facts say.
        """;
    var ledger = TestLedgerClient.create().seed(interaction("compliance-summary-agent", message, reply));
    var result = EvaluatorTestKit.of(() -> new ComplianceWordingEvaluator(ledger, null))
        .evaluate(new Subject.Interaction("i-1", "compliance-summary-agent", Optional.empty()));
    assertThat(result.isComplete()).isTrue();
    return result.getEvaluation();
  }

  @Test
  public void theLedgerShowsWhetherTheComplianceWordingStaysWithinTheFacts() {
    assertThat(wordingVerdict("Control custom-cl0003 had 4 decisions in the last 90 days.").passed()).isTrue();
    var invented = wordingVerdict("Control custom-cl0003 had 7 decisions and the system is fully compliant.");
    assertThat(invented.passed()).isFalse();
    assertThat(invented.explanation()).contains("the number 7").contains("compliant");
  }
}

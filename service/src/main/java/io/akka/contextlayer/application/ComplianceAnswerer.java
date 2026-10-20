package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ComplianceAnswer;
import io.akka.contextlayer.governance.ComplianceAnswer.Fact;
import io.akka.contextlayer.governance.ComplianceAnswer.Intent;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Answers an auditor's question in four steps that do not trust each other: a model chooses which fixed question
 * was asked; the choice is checked against the catalog; the facts are computed by templates from recorded data;
 * a second model words the facts, and its words are checked against them. When the words claim more than the facts
 * say, the facts are given in plain form instead, and that is recorded.
 */
public class ComplianceAnswerer {

  public static final int MAX_QUESTION = 1000;

  /**
   * @param facts what the records say, and the records each comes from
   * @param limits what the records do not show
   * @param summary the facts in words, or in plain form when the words could not be supported
   * @param summaryChecked false when {@code summary} is the plain form because the model's words claimed more than the facts
   * @param clarification set instead of the rest when the question could not be placed
   * @param supported the questions that can be answered, given when the question could not be placed
   */
  public record Response(
      String intent, String controlId, int periodDays, List<Fact> facts, List<String> limits, String summary,
      boolean summaryChecked, List<String> unsupported, String clarification, List<String> supported) {}

  private final ComponentClient componentClient;
  private final ControlCatalog catalog;
  private final ComplianceFacts facts;

  public ComplianceAnswerer(ComponentClient componentClient, ControlCatalog catalog) {
    this.componentClient = componentClient;
    this.catalog = catalog;
    this.facts = new ComplianceFacts(componentClient, catalog);
  }

  public Response ask(String project, String question, String actor) {
    var id = UUID.randomUUID().toString();
    var choice = componentClient.forAgent().inSession(ScopedId.of(project, "ca", id + "-i"))
        .method(ComplianceIntentAgent::classify)
        .invoke(new ComplianceIntentAgent.Request(question, intents(), controls()));

    var intent = choice == null ? java.util.Optional.<Intent>empty() : Intent.parse(choice.intent());
    if (intent.isEmpty()) {
      return unplaced(project, id, actor, choice == null || blank(choice.clarification())
          ? "I can answer the questions listed. Which of them do you mean?" : choice.clarification().trim());
    }
    var control = choice.controlId() == null ? java.util.Optional.<io.akka.contextlayer.governance.ControlDef>empty()
        : catalog.find(choice.controlId().trim());
    if (intent.get().needsControl() && control.isEmpty()) {
      return unplaced(project, id, actor, "Which control do you mean? Its id is one of: "
          + catalog.controls().stream().map(c -> c.rowId()).collect(Collectors.joining(", ")) + ".");
    }

    int days = ComplianceAnswer.period(choice.periodDays());
    var computed = facts.of(project, intent.get(), control.map(c -> c.rowId()).orElse(""), days, System.currentTimeMillis());
    var worded = componentClient.forAgent().inSession(ScopedId.of(project, "ca", id + "-s"))
        .method(ComplianceSummaryAgent::summarize)
        .invoke(new ComplianceSummaryAgent.Request(question,
            computed.facts().stream().map(Fact::text).collect(Collectors.joining("\n")),
            String.join("\n", computed.limits())));

    var check = worded == null || worded.isBlank() ? new ComplianceAnswer.Check(false, List.of("no answer was given"))
        : ComplianceAnswer.check(worded, computed);
    var summary = check.ok() ? worded.strip() : ComplianceAnswer.plain(computed);
    record(project, id, actor, intent.get().name() + (computed.controlId().isEmpty() ? "" : " " + computed.controlId()),
        check.ok() ? "PASS" : "BLOCK", check.ok() ? "CONTINUED" : "FALLBACK_USED", check.ok() ? "answered" : "unsupported-claim");
    return new Response(intent.get().name(), computed.controlId(), days, computed.facts(), computed.limits(), summary, check.ok(),
        check.unsupported(), "", List.of());
  }

  private Response unplaced(String project, String id, String actor, String clarification) {
    record(project, id, actor, "unplaced", "PASS", "CONTINUED", "needs-clarification");
    return new Response("", "", 0, List.of(), List.of(), "", false, List.of(), clarification, supported());
  }

  private void record(String project, String id, String actor, String subject, String decision, String consequence, String reason) {
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(project, Controls.COMPLIANCE_ANSWER, id,
        EvaluationMode.ENFORCING, decision, consequence, reason, subject, actor, "", "", false, "", 0));
  }

  private String intents() {
    return Stream.of(Intent.values()).map(i -> "- " + i.name() + (i.needsControl() ? " (needs a control)" : "") + ": " + i.description())
        .collect(Collectors.joining("\n"));
  }

  private String controls() {
    return catalog.controls().stream().map(c -> "- " + c.rowId() + ": " + c.name()
        + (c.corpusRef() == null || c.corpusRef().isBlank() ? "" : " [" + c.corpusRef() + "]")).collect(Collectors.joining("\n"));
  }

  private static List<String> supported() {
    return Stream.of(Intent.values()).map(i -> i.name() + ": " + i.description()).toList();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}

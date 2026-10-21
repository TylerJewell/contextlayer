package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.application.QuestionAgent;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A request in words becoming a checked question, with the model mocked and the data real. */
public class QuestionDraftIntegrationTest extends LayerTestBase {

  private static final TestModelProvider model = new TestModelProvider();

  private static final String GOOD = """
      {"entityType":"Claim",
       "where":[{"field":"status","op":"EQ","value":"OPEN"},{"field":"daysOpen","op":"GT","value":"30"}],
       "links":[{"entityType":"Account","where":[{"field":"balance","op":"GT","value":"250000"}]}],
       "returnFields":["Claim.claimId","Claim.daysOpen","Account.balance"],
       "limit":50,"clarification":"","explanation":"Open claims over 30 days whose client holds more than 250,000."}""";

  private static final String UNKNOWN_FIELD = """
      {"entityType":"Claim","where":[{"field":"severity","op":"EQ","value":"high"}],"links":[],
       "returnFields":["Claim.claimId"],"limit":10,"clarification":"","explanation":"x"}""";

  private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings().withModelProvider(QuestionAgent.class, model);
  }

  @BeforeEach
  void resetModel() {
    requests.clear();
    model.reset();
  }

  private void modelAnswers(String json) {
    model.whenMessage(message -> {
      requests.add(message);
      return true;
    }).reply(new TestModelProvider.AiResponse(json));
  }

  private String ready() {
    return connectBoth(newOrg());
  }

  private ContextLayerEndpoint.DraftResponse draft(String org, String text) {
    return post("alice", "/orgs/" + org + "/questions/draft", new ContextLayerEndpoint.DraftRequest(text),
        ContextLayerEndpoint.DraftResponse.class);
  }

  private GovernanceEndpoint.EvidenceExport evidence(String org, String control) {
    return get("/orgs/" + org + "/governance/evidence?control=" + control, GovernanceEndpoint.EvidenceExport.class);
  }

  @Test
  public void aRequestInWordsBecomesAQuestionThatIsReadyToRun() {
    var org = ready();
    modelAnswers(GOOD);

    var drafted = draft(org, "stalled claims over a month for wealthy clients");

    assertThat(drafted.problems()).isEmpty();
    assertThat(drafted.clarification()).isEmpty();
    assertThat(drafted.explanation()).contains("Open claims over 30 days");
    var q = drafted.question();
    assertThat(q.entityType()).isEqualTo("Claim");
    assertThat(q.where()).extracting(ContextLayerEndpoint.ConditionRequest::field, ContextLayerEndpoint.ConditionRequest::op)
        .containsExactly(tuple("status", "EQ"), tuple("daysOpen", "GT"));
    assertThat(q.where().get(1).value()).isEqualTo(30);
    assertThat(q.links().get(0).where().get(0).value()).isEqualTo(250000);
    assertThat(q.limit()).isEqualTo(50);
    assertThat(requests).containsExactly("stalled claims over a month for wealthy clients");

    var answer = post("alice", "/orgs/" + org + "/questions",
        new ContextLayerEndpoint.QuestionRequest(q.entityType(), q.where(), q.links(), q.returnFields(), q.limit()),
        ContextLayerEndpoint.AnswerResponse.class);
    assertThat(answer.rows()).isNotEmpty();
  }

  @Test
  public void aDraftThatNamesAFieldThatDoesNotExistIsSentBackOnceAndTheRepairIsAccepted() {
    var org = ready();
    var calls = new AtomicInteger();
    model.fixedResponse(input -> new TestModelProvider.AiResponse(calls.incrementAndGet() == 1 ? UNKNOWN_FIELD : GOOD));

    var drafted = draft(org, "serious claims");

    assertThat(drafted.question()).isNotNull();
    assertThat(drafted.problems()).isEmpty();
    assertThat(calls.get()).isGreaterThanOrEqualTo(2);
  }

  @Test
  public void aDraftThatStaysInvalidIsRefusedWithTheReasonsAndRecordedAsBlocked() {
    var org = ready();
    modelAnswers(UNKNOWN_FIELD);

    var drafted = draft(org, "serious claims");

    assertThat(drafted.question()).isNull();
    assertThat(drafted.problems()).anyMatch(p -> p.contains("severity"));
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var rows = evidence(org, Controls.DRAFT_SCHEMA_CHECK).evidence();
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).decision()).isEqualTo("BLOCK");
      assertThat(rows.get(0).reasonCode()).isEqualTo("invalid-draft");
    });
  }

  @Test
  public void aWrongKindOfValueAndAnUnknownTypeAreProblemsNotQuestions() {
    var org = ready();
    modelAnswers("""
        {"entityType":"Policy","where":[],"links":[],"returnFields":["Policy.id"],"limit":5,
         "clarification":"","explanation":"x"}""");
    assertThat(draft(org, "policies").problems()).anyMatch(p -> p.contains("Policy") && p.contains("not one of"));

    model.reset();
    modelAnswers("""
        {"entityType":"Claim","where":[{"field":"daysOpen","op":"GT","value":"a month"}],"links":[],
         "returnFields":["Claim.claimId"],"limit":5,"clarification":"","explanation":"x"}""");
    assertThat(draft(org, "old claims").problems()).anyMatch(p -> p.contains("daysOpen") && p.contains("number"));
  }

  @Test
  public void aModelThatAsksForClarificationPassesItOnWithoutAQuestion() {
    var org = ready();
    modelAnswers("""
        {"entityType":"","where":[],"links":[],"returnFields":[],"limit":null,
         "clarification":"Which claims do you mean: open ones, or all of them?","explanation":""}""");

    var drafted = draft(org, "the bad ones");

    assertThat(drafted.question()).isNull();
    assertThat(drafted.problems()).isEmpty();
    assertThat(drafted.clarification()).startsWith("Which claims");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(evidence(org, Controls.SHAPE_ONLY_DRAFTING).evidence()).extracting(GovernanceEndpoint.EvidenceItem::reasonCode)
            .containsExactly("shape-only"));
    assertThat(evidence(org, Controls.DRAFT_SCHEMA_CHECK).evidence()).isEmpty();
  }

  @Test
  public void aSuccessfulDraftIsEvidenceThatTheModelSawOnlyTheShapeOfTheData() {
    var org = ready();
    modelAnswers(GOOD);

    draft(org, "stalled claims");

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var rows = evidence(org, Controls.SHAPE_ONLY_DRAFTING).evidence();
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).decision()).isEqualTo("PASS");
      assertThat(rows.get(0).eventType()).isEqualTo("GuardrailDecisionRecorded");
      assertThat(rows.get(0).boundary()).isEqualTo("BEFORE_LLM_CALL");
      assertThat(rows.get(0).toString()).doesNotContain("stalled claims");
      var checked = evidence(org, Controls.DRAFT_SCHEMA_CHECK).evidence();
      assertThat(checked).singleElement().satisfies(e -> {
        assertThat(e.decision()).isEqualTo("PASS");
        assertThat(e.boundary()).isEqualTo("AFTER_LLM_RESPONSE");
      });
    });
  }

  @Test
  public void aModelIsShownAndMayDraftOnlyWhatTheAskerMayRead() {
    var org = ready();
    // Only the claims domain exists, and it is readable by administrators, who are asking.
    post("root", "/orgs/" + org + "/domains/claims",
        new io.akka.contextlayer.api.DomainEndpoint.DomainRequest("", List.of("Claim"), List.of("admin"), List.of(), "admins read claims"),
        io.akka.contextlayer.api.DomainEndpoint.DomainView.class);
    modelAnswers(GOOD);

    var drafted = draft(org, "stalled claims for wealthy clients");

    assertThat(drafted.question()).isNull();
    assertThat(drafted.problems()).anyMatch(p -> p.contains("Account"));
  }

  @Test
  public void withNoDataReadYetThereIsNothingToDraftFrom() {
    var org = newOrg();
    modelAnswers(GOOD);

    var drafted = draft(org, "anything");

    assertThat(drafted.question()).isNull();
    assertThat(drafted.problems()).anyMatch(p -> p.contains("no data has been read"));
    assertThat(requests).isEmpty();
  }

  @Test
  public void aRequestThatIsMissingOrTooLongIsRefused() {
    var org = newOrg();

    assertThat(postStatus("alice", "/orgs/" + org + "/questions/draft", new ContextLayerEndpoint.DraftRequest(" "))).isEqualTo(400);
    assertThat(postStatus("alice", "/orgs/" + org + "/questions/draft",
        new ContextLayerEndpoint.DraftRequest("x".repeat(1001)))).isEqualTo(400);
  }
}

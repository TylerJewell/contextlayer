package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.application.ComplianceAnswerer;
import io.akka.contextlayer.application.ComplianceIntentAgent;
import io.akka.contextlayer.application.ComplianceSummaryAgent;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An auditor's question: a model chooses the question, templates compute the facts, a second model words them, and the words are checked. */
public class ComplianceAskIntegrationTest extends LayerTestBase {

  private static final TestModelProvider intent = new TestModelProvider();
  private static final TestModelProvider summary = new TestModelProvider();
  private static final String CONTROL = Controls.ACTION_APPROVAL;
  private final AtomicInteger summaries = new AtomicInteger();

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings().withModelProvider(ComplianceIntentAgent.class, intent).withModelProvider(ComplianceSummaryAgent.class, summary);
  }

  @BeforeEach
  void reset() {
    intent.reset();
    summary.reset();
    summaries.set(0);
  }

  private void choose(String json) {
    intent.fixedResponse(input -> new TestModelProvider.AiResponse(json));
  }

  private void word(String text) {
    summary.fixedResponse(input -> {
      summaries.incrementAndGet();
      return new TestModelProvider.AiResponse(text);
    });
  }

  private ComplianceAnswerer.Response ask(String org, String text) {
    return post("auditor", "/orgs/" + org + "/governance/ask", new GovernanceEndpoint.AskRequest(text), ComplianceAnswerer.Response.class);
  }

  private void say(String org, String who, String role, String path, Object body) {
    var response = wire().POST("/orgs/" + org + "/governance/controls/" + CONTROL + path)
        .addHeader("Authorization", Tokens.bearer(who, org, role)).withRequestBody(body).invoke();
    assertThat(response.status().intValue()).as(response.body().utf8String()).isEqualTo(200);
  }

  private String claimed() {
    var org = newOrg();
    say(org, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("the owner takes this on"));
    say(org, "erin", "admin", "/assertion", new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded",
        List.of(new GovernanceEndpoint.ExpectationView("override procedure", 365)), "human reviews are the evidence"));
    return org;
  }

  @Test
  public void aQuestionAboutAControlIsAnsweredFromFactsAndTheWordsAreChecked() {
    var org = claimed();
    choose("{\"intent\":\"CONTROL_STATUS\",\"controlId\":\"" + CONTROL + "\",\"periodDays\":null,\"clarification\":\"\"}");
    word("Dana accepted control " + CONTROL + " and Erin said which records count as evidence. The override procedure is still needed, and nothing shows the control ran.");

    var answer = ask(org, "Can a person override the assistant, and who signed off?");

    assertThat(answer.intent()).isEqualTo("CONTROL_STATUS");
    assertThat(answer.controlId()).isEqualTo(CONTROL);
    assertThat(answer.summaryChecked()).isTrue();
    assertThat(answer.summary()).contains("Dana accepted");
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("accepted by dana"));
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("made by erin"));
    assertThat(answer.limits()).contains("Still needed: override procedure: none attached.");
    assertThat(answer.limits()).anyMatch(l -> l.contains("No enforcing record of this control exists"));
    assertThat(answer.facts()).allSatisfy(f -> assertThat(f.refs()).isNotEmpty());
  }

  @Test
  public void aSummaryThatClaimsMoreThanTheFactsSayIsReplacedByTheFactsAndRecorded() {
    var org = claimed();
    choose("{\"intent\":\"CONTROL_STATUS\",\"controlId\":\"" + CONTROL + "\",\"periodDays\":null,\"clarification\":\"\"}");
    word("Control " + CONTROL + " is fully compliant, with 99 records and no gaps.");

    var answer = ask(org, "Are we compliant?");

    assertThat(answer.summaryChecked()).isFalse();
    assertThat(answer.unsupported()).contains("the number 99").contains("the claim \"fully compliant\"");
    assertThat(answer.summary()).doesNotContain("99").contains("accepted by dana").contains("What the records do not show:");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var evidence = get("/orgs/" + org + "/governance/evidence?control=" + Controls.FACTS_ONLY_WORDING, GovernanceEndpoint.EvidenceExport.class).evidence();
      assertThat(evidence).singleElement().satisfies(e -> {
        assertThat(e.decision()).isEqualTo("BLOCK");
        assertThat(e.reasonCode()).isEqualTo("unsupported-claim");
        assertThat(e.actor()).isEqualTo("auditor");
      });
    });
  }

  @Test
  public void aQuestionNoneOfTheListedOnesFitsIsHandedBackWithTheList() {
    var org = newOrg();
    choose("{\"intent\":\"\",\"controlId\":\"\",\"periodDays\":null,\"clarification\":\"I can only answer questions about the controls. Which do you mean?\"}");
    word("never used");

    var answer = ask(org, "What is the weather in Boston?");

    assertThat(answer.clarification()).startsWith("I can only answer questions about the controls");
    assertThat(answer.supported()).hasSize(5).anyMatch(s -> s.startsWith("GAPS"));
    assertThat(answer.facts()).isEmpty();
    assertThat(summaries.get()).as("nothing was worded").isZero();
  }

  @Test
  public void aQuestionAboutAControlThatNamesNoneAsksWhichAndAnInventedIdIsNotTaken() {
    var org = newOrg();
    word("never used");
    choose("{\"intent\":\"CONTROL_STATUS\",\"controlId\":\"\",\"periodDays\":null,\"clarification\":\"\"}");
    assertThat(ask(org, "How is it doing?").clarification()).startsWith("Which control do you mean?").contains(CONTROL);

    choose("{\"intent\":\"CONTROL_STATUS\",\"controlId\":\"custom-cl9999\",\"periodDays\":null,\"clarification\":\"\"}");
    assertThat(ask(org, "How is custom-cl9999 doing?").clarification()).startsWith("Which control do you mean?");
    assertThat(summaries.get()).isZero();
  }

  @Test
  public void theGapsQuestionNamesTheControlsThatFallBetweenTheClaims() {
    var org = claimed();
    choose("{\"intent\":\"GAPS\",\"controlId\":\"\",\"periodDays\":null,\"clarification\":\"\"}");
    word("Several controls are not accepted, and one has documents still missing.");

    var answer = ask(org, "Where are the gaps?");

    assertThat(answer.intent()).isEqualTo("GAPS");
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("not accepted for the project").contains(Controls.MASK_PERSONAL_DATA));
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("expected documents missing or too old").contains(CONTROL));
    assertThat(answer.summaryChecked()).isTrue();
  }

  @Test
  public void theIntegrityQuestionStatesWhichStoresAreTamperEvidentAndWhatCannotBeDetected() {
    var org = claimed();
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/governance/integrity", GovernanceEndpoint.IntegrityReport.class).count())
            .isEqualTo(get("/orgs/" + org + "/governance/evidence", GovernanceEndpoint.EvidenceExport.class).count()));
    choose("{\"intent\":\"INTEGRITY\",\"controlId\":\"\",\"periodDays\":null,\"clarification\":\"\"}");
    word("The evidence ledger is hash chained and the other stores have no digest.");

    var answer = ask(org, "Could someone alter the evidence?");

    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("HashChained"));
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).contains("NoIntegrity"));
    assertThat(answer.facts()).anySatisfy(f -> assertThat(f.text()).containsPattern("holds \\d+ records").contains("none altered"));
    assertThat(answer.limits()).anyMatch(l -> l.contains("rewrite the whole ledger"));
  }

  @Test
  public void aMissingOrTooLongQuestionIsRefused() {
    var org = newOrg();

    assertThat(postStatus("auditor", "/orgs/" + org + "/governance/ask", new GovernanceEndpoint.AskRequest(" "))).isEqualTo(400);
    assertThat(postStatus("auditor", "/orgs/" + org + "/governance/ask", new GovernanceEndpoint.AskRequest("x".repeat(1001)))).isEqualTo(400);
  }
}

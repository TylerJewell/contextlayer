package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.contextlayer.api.CaseEndpoint;
import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint.ConditionRequest;
import io.akka.contextlayer.application.ClassifierAgent;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An assistant's playbook run on a new case, end to end, with the model mocked: what the model is
 * shown, that only an allowed label is accepted, how Shadow and Live differ, and that the case
 * timeline tells the story. No test calls a real model.
 */
public class PlaybookFlowIntegrationTest extends LayerTestBase {

  private static final TestModelProvider model = new TestModelProvider();

  /** Label the case, branch on the label, request an update when blocked, and note the judgment. */
  private static final String PLAYBOOK = """
      {"steps":[
        {"id":"judge","type":"classify","instruction":"How badly is the client's claim affecting them?",
         "fields":["Claim.daysOpen"],"labels":["BLOCKED","DEGRADED","MINOR"],"store":"impact","next":"route"},
        {"id":"route","type":"branch","var":"impact","cases":{"BLOCKED":"attach","DEGRADED":"note"},"otherwise":"end"},
        {"id":"attach","type":"act","actionType":"record.update","payload":"claim {Claim.claimId} is {impact}","next":"note"},
        {"id":"note","type":"annotate","text":"impact judged {impact}","next":"end"}]}""";

  /** Every message the model was sent, so a test can check what it was shown. */
  private final List<String> modelInputs = new CopyOnWriteArrayList<>();

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings().withModelProvider(ClassifierAgent.class, model);
  }

  @BeforeEach
  void resetModel() {
    modelInputs.clear();
  }

  private void modelAnswers(String label) {
    model.fixedResponse("{\"label\":\"" + label + "\",\"reason\":\"because\"}");
  }

  private void modelAnswersAndRecords(String label) {
    model.whenMessage(message -> {
      modelInputs.add(message);
      return true;
    }).reply(new TestModelProvider.AiResponse("{\"label\":\"" + label + "\",\"reason\":\"because\"}"));
  }

  /** An assistant owned by alice with the playbook approved, in the given stage. */
  private String assistant(String org, AssistantStage stage) {
    return assistant(org, stage, PLAYBOOK);
  }

  private String assistant(String org, AssistantStage stage, String playbook) {
    var id = "claims-watch";
    var base = "/orgs/" + org + "/assistants/" + id;
    post("alice", base, new ContextLayerEndpoint.CreateAssistantRequest("keep claims moving"),
        ContextLayerEndpoint.AssistantResponse.class);
    var release = post("alice", base + "/releases", new ContextLayerEndpoint.AddReleaseRequest(playbook),
        ContextLayerEndpoint.ReleaseResponse.class);
    if (stage != AssistantStage.DRAFT) {
      post("alice", base + "/releases/" + release.releaseId() + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test"),
          ContextLayerEndpoint.AssistantResponse.class);
      post("alice", base + "/stage", stageRequest(stage),
          ContextLayerEndpoint.AssistantResponse.class);
    }
    return id;
  }

  /** Connects the sources, creates a monitor handled by the assistant, and returns the new case. */
  private CaseEndpoint.CaseDetail caseFor(String org, String assistantId, String awaitedTimelineText) {
    return caseFor(org, assistantId, awaitedTimelineText, stalledClaimsWithWealth());
  }

  private CaseEndpoint.CaseDetail caseFor(
      String org, String assistantId, String awaitedTimelineText, ContextLayerEndpoint.QuestionRequest question) {
    connectBoth(org);
    post("alice", "/orgs/" + org + "/monitors/m1",
        new MonitorEndpoint.MonitorRequest("stalled-claims", question, "Claim.claimId", 60,
            assistantId, "Claims Team B"), MonitorEndpoint.MonitorResponse.class);
    var detail = new CaseEndpoint.CaseDetail[1];
    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      var list = get("/orgs/" + org + "/cases", CaseEndpoint.CaseList.class).items();
      assertThat(list).hasSize(1);
      detail[0] = get("/orgs/" + org + "/cases/" + list.get(0).caseId(), CaseEndpoint.CaseDetail.class);
      assertThat(detail[0].timeline()).extracting(CaseEndpoint.TimelineEntry::text)
          .anyMatch(t -> t.contains(awaitedTimelineText));
    });
    return detail[0];
  }

  private static List<String> timeline(CaseEndpoint.CaseDetail detail) {
    return detail.timeline().stream().map(CaseEndpoint.TimelineEntry::text).toList();
  }

  @Test
  public void aShadowAssistantJudgesTheCaseAndRecordsWhatItWouldDoWithoutSendingAnything() {
    modelAnswers("BLOCKED");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.SHADOW);

    var detail = caseFor(org, assistant, "impact judged BLOCKED");

    assertThat(timeline(detail)).anyMatch(t -> t.contains("judged impact = BLOCKED"))
        .anyMatch(t -> t.contains("requested record.update: WOULD_DO"));
    assertThat(detail.timeline()).extracting(CaseEndpoint.TimelineEntry::actor).contains("assistant claims-watch");
    assertThat(executor.calls).noneMatch(c -> c.startsWith(org));
  }

  @Test
  public void theTimelineLeadsToTheModelCallInTheRuntimeLedgerAndItsVerdict() {
    modelAnswers("BLOCKED");
    var org = newOrg();
    var detail = caseFor(org, assistant(org, AssistantStage.SHADOW), "impact judged BLOCKED");

    var judged = timeline(detail).stream().filter(t -> t.contains("judged impact = BLOCKED")).findFirst().orElseThrow();
    var ref = java.util.regex.Pattern.compile("ledger:([\\w.-]+)").matcher(judged);
    assertThat(ref.find()).as("the judgment names its ledger record: %s", judged).isTrue();

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var call = get("/orgs/" + org + "/governance/interactions/" + ref.group(1), GovernanceEndpoint.InteractionView.class);
      assertThat(call.agent()).isEqualTo("classifier-agent");
      assertThat(call.systemMessage()).contains("Allowed labels: BLOCKED, DEGRADED, MINOR");
      assertThat(call.reply()).contains("BLOCKED");
      assertThat(call.verdicts()).anySatisfy(v -> {
        assertThat(v.controlId()).isEqualTo("custom-cl0002");
        assertThat(v.passed()).isTrue();
      });
    });
    var elsewhere = "/orgs/" + adopt("org-other-" + org) + "/governance/interactions/" + ref.group(1);
    assertThat(wire().GET(elsewhere).addHeader("Authorization", Tokens.admin("reader", elsewhere)).invoke().status().intValue())
        .as("another project cannot read this project's model call").isEqualTo(404);
  }

  @Test
  public void aLiveAssistantsRiskyActionWaitsForAPersonThenRunsAndTheCaseShowsIt() {
    modelAnswers("BLOCKED");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.LIVE);

    var detail = caseFor(org, assistant, "requested record.update: AWAITING_APPROVAL");

    assertThat(executor.calls).noneMatch(c -> c.startsWith(org));
    var pending = new ContextLayerEndpoint.ActionList[1];
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      pending[0] = get("/orgs/" + org + "/approvals", ContextLayerEndpoint.ActionList.class);
      assertThat(pending[0].items()).hasSize(1);
    });
    var actionKey = pending[0].items().get(0).actionKey();
    assertThat(pending[0].items().get(0).actionType()).isEqualTo("record.update");

    post("bob", "/orgs/" + org + "/assistants/" + assistant + "/actions/" + actionKey + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test"),
        ContextLayerEndpoint.ActionAccepted.class);

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var now = get("/orgs/" + org + "/cases/" + detail.caseId(), CaseEndpoint.CaseDetail.class);
      assertThat(timeline(now)).anyMatch(t -> t.contains("record.update approved by bob"))
          .anyMatch(t -> t.contains("record.update succeeded"));
    });
    assertThat(executor.calls).contains(org + ":record.update:claim 1 is BLOCKED");
  }

  @Test
  public void aLabelThatLeadsNowhereEndsThePlaybookWithoutTakingAction() {
    modelAnswers("MINOR");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.LIVE);

    var detail = caseFor(org, assistant, "judged impact = MINOR");

    // MINOR is allowed but the branch has no route for it, so the playbook ends there.
    assertThat(timeline(detail)).noneMatch(t -> t.contains("requested record.update"));
    assertThat(executor.calls).noneMatch(c -> c.startsWith(org));
  }

  @Test
  public void aLabelOutsideTheAllowedSetIsNeverActedOn() {
    modelAnswers("CATASTROPHIC");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.LIVE);

    var detail = caseFor(org, assistant, "it failed after retries");

    assertThat(timeline(detail)).anyMatch(t -> t.contains("stopped at step judge"))
        .noneMatch(t -> t.contains("requested record.update"));
    assertThat(executor.calls).noneMatch(c -> c.startsWith(org));
  }

  @Test
  public void theModelSeesOnlyTheFieldsThePlaybookNames() {
    modelAnswersAndRecords("DEGRADED");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.SHADOW);

    caseFor(org, assistant, "impact judged DEGRADED");

    assertThat(modelInputs).isNotEmpty();
    var shown = String.join("\n", modelInputs);
    assertThat(shown).contains("Claim.daysOpen: 41")
        .doesNotContain("Account.balance").doesNotContain("412000")
        .doesNotContain("pat@example.com").doesNotContain("Claim.claimId");
  }

  @Test
  public void aDraftAssistantRunsNothingAndTheCaseSaysSo() {
    modelAnswers("BLOCKED");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.DRAFT);

    var detail = caseFor(org, assistant, "the playbook was not run");

    assertThat(timeline(detail)).noneMatch(t -> t.contains("judged impact"));
    assertThat(executor.calls).noneMatch(c -> c.startsWith(org));
  }

  @Test
  public void aReleaseThatIsNotAValidPlaybookIsRefusedWhenItIsAdded() {
    var org = newOrg();
    post("alice", "/orgs/" + org + "/assistants/a1", new ContextLayerEndpoint.CreateAssistantRequest("purpose"),
        ContextLayerEndpoint.AssistantResponse.class);
    var base = "/orgs/" + org + "/assistants/a1/releases";

    assertThat(postStatus("alice", base, new ContextLayerEndpoint.AddReleaseRequest("run this script"))).isEqualTo(400);
    assertThat(postStatus("alice", base, new ContextLayerEndpoint.AddReleaseRequest(
        PLAYBOOK.replace("\"next\":\"route\"", "\"next\":\"nowhere\"")))).isEqualTo(400);
    assertThat(postStatus("alice", base, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK))).isEqualTo(200);
  }

  /** A playbook that shows the model a field holding an email address. */
  private static final String EMAIL_PLAYBOOK = """
      {"steps":[
        {"id":"judge","type":"classify","instruction":"How badly is the client's claim affecting them?",
         "fields":["Claim.daysOpen","Claim.email"],"labels":["BLOCKED","DEGRADED","MINOR"],"store":"impact","next":"note"},
        {"id":"note","type":"annotate","text":"impact judged {impact}","next":"end"}]}""";

  private ContextLayerEndpoint.QuestionRequest stalledClaimsWithEmail() {
    return new ContextLayerEndpoint.QuestionRequest("Claim",
        List.of(new ConditionRequest("status", "EQ", "OPEN"), new ConditionRequest("daysOpen", "GT", 40)),
        List.of(), List.of("Claim.claimId", "Claim.daysOpen", "Claim.email"), 100);
  }

  private GovernanceEndpoint.EvidenceExport evidence(String org, String control) {
    return get("/orgs/" + org + "/governance/evidence?control=" + control, GovernanceEndpoint.EvidenceExport.class);
  }

  @Test
  public void personalDataIsMaskedBeforeTheModelAndTheMaskingAndTheLabelCheckAreEvidence() {
    modelAnswersAndRecords("DEGRADED");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.SHADOW, EMAIL_PLAYBOOK);

    caseFor(org, assistant, "impact judged DEGRADED", stalledClaimsWithEmail());

    var shown = String.join("\n", modelInputs);
    assertThat(shown).contains("Claim.daysOpen: 41").doesNotContain("pat@example.com");
    assertThat(shown).contains("*".repeat("pat@example.com".length()));

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var masking = evidence(org, Controls.MASK_PERSONAL_DATA).evidence();
      assertThat(masking).isNotEmpty().allSatisfy(e -> {
        assertThat(e.runtimeConsequence()).isEqualTo("MASKED");
        assertThat(e.reasonCode()).isEqualTo("email 1");
        assertThat(e.evaluationMode()).isEqualTo("ENFORCING");
        assertThat(e.verdict()).isEqualTo("upheld");
      });
      var labels = evidence(org, Controls.LABEL_ALLOWLIST).evidence();
      assertThat(labels).isNotEmpty().allSatisfy(e -> {
        assertThat(e.decision()).isEqualTo("PASS");
        assertThat(e.eventType()).isEqualTo("GuardrailDecisionRecorded");
        assertThat(e.boundary()).isEqualTo("WORKFLOW_STEP");
      });
    });
  }

  @Test
  public void aLabelOutsideTheAllowedSetIsRecordedAsABlockedEvaluation() {
    modelAnswers("CATASTROPHIC");
    var org = newOrg();
    var assistant = assistant(org, AssistantStage.LIVE);

    caseFor(org, assistant, "it failed after retries");

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var labels = evidence(org, Controls.LABEL_ALLOWLIST).evidence();
      assertThat(labels).isNotEmpty().allSatisfy(e -> {
        assertThat(e.decision()).isEqualTo("BLOCK");
        assertThat(e.runtimeConsequence()).isEqualTo("BLOCKED");
        assertThat(e.reasonCode()).isEqualTo("label-not-allowed");
        assertThat(e.verdict()).isEqualTo("upheld");
      });
    });
  }
}

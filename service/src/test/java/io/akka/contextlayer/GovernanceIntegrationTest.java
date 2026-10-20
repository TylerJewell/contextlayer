package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.ActionWorkflow;
import io.akka.contextlayer.application.HumanEventEntity;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The governance of an action approval and of the changes people authorize, end to end: the human
 * event an approval waits on, who may act on it and how, and the evidence and compliance that
 * result. Models and sources are not involved.
 */
public class GovernanceIntegrationTest extends TestKitSupport {
private Wire wire() {    return new Wire(httpClient);  }

  private static final String PLAYBOOK =
      "{\"steps\":[{\"id\":\"note\",\"type\":\"annotate\",\"text\":\"ok\",\"next\":\"end\"}]}";

  private record Reply(int status, JsonNode body) {}

  private static final RecordingNotifier NOTIFIER = new RecordingNotifier();

  private static final ActionExecutor EXECUTOR = (org, type, payload) -> ActionExecutor.Result.ok("done");

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestDependencies.settings().withDependencyProvider(
        TestDependencies.provider(EXECUTOR, TestDependencies.NO_SOURCES, TestDependencies.NO_STORE, NOTIFIER));
  }

  // ---- helpers

  private static String newOrg() {
    return "org-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String as(String subject, String org, String... roles) {
    return Tokens.bearer(subject, org, roles);
  }

  private Reply send(String method, String path, String token, Object body) {
    var request = "GET".equals(method) ? wire().GET(path) : wire().POST(path);
    request = request.addHeader("Authorization", token);
    if (!"GET".equals(method)) {
      request = request.withRequestBody(ContentTypes.APPLICATION_JSON, bytes(body == null ? Map.of() : body));
    }
    var response = request.invoke();
    var text = response.body().utf8String();
    return new Reply(response.status().intValue(), text.isBlank() ? null : parse(text));
  }

  /** The runtime answers some errors in plain text; those come back as a text node. */
  private static JsonNode parse(String text) {
    try {
      return JsonSupport.getObjectMapper().readTree(text);
    } catch (Exception e) {
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }
  }

  private static byte[] bytes(Object body) {
    try {
      return JsonSupport.getObjectMapper().writeValueAsBytes(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Reply get(String path, String token) {
    return send("GET", path, token, null);
  }

  private Reply post(String path, String token, Object body) {
    return send("POST", path, token, body);
  }

  /** An assistant owned by alice with an approved release, in the given stage. */
  private String assistant(String org, AssistantStage stage) {
    var alice = as("alice", org, "operator", "approver");
    var a = "asst-" + UUID.randomUUID().toString().substring(0, 8);
    var base = "/orgs/" + org + "/assistants/" + a;
    assertThat(post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("watch claims")).status()).isEqualTo(200);
    var release = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK));
    var releaseId = release.body().get("releaseId").asText();
    assertThat(post(base + "/releases/" + releaseId + "/approve", alice,
        new ContextLayerEndpoint.DecisionRequest("the playbook only annotates")).status()).isEqualTo(200);
    assertThat(post(base + "/stage", alice, new ContextLayerEndpoint.StageRequest(stage, "ready for use")).status())
        .isEqualTo(200);
    return a;
  }

  /** Requests a risky action as alice and waits until its approval event is open. Returns the event id. */
  private String requestApproval(String org, String assistant, String key) {
    var alice = as("alice", org, "operator", "approver");
    assertThat(post("/orgs/" + org + "/assistants/" + assistant + "/actions/" + key, alice,
        new ContextLayerEndpoint.ActionRequest("record.update", "payload-" + key)).status()).isEqualTo(200);
    var eventId = ActionWorkflow.humanEventId(org, assistant, key);
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/oversight/events/" + eventId, as("bob", org, "approver")).status()).isEqualTo(200));
    return eventId;
  }

  private String ledgerStatus(String org, String assistant, String key) {
    return get("/orgs/" + org + "/assistants/" + assistant + "/actions/" + key, as("reader", org, "viewer"))
        .body().get("status").asText();
  }

  private void awaitLedger(String org, String assistant, String key, String status) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(
        () -> assertThat(ledgerStatus(org, assistant, key)).isEqualTo(status));
  }

  private JsonNode event(String org, String id) {
    return get("/oversight/events/" + id, as("reader", org, "viewer")).body();
  }

  private void awaitEventState(String org, String id, String state) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(
        () -> assertThat(event(org, id).get("state").asText()).isEqualTo(state));
  }

  private JsonNode evidence(String org, String control) {
    return get("/orgs/" + org + "/governance/evidence?control=" + control, as("reader", org, "viewer")).body();
  }

  private JsonNode awaitEvidence(String org, String control, int atLeast) {
    var seen = new JsonNode[1];
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      seen[0] = evidence(org, control);
      assertThat(seen[0].get("count").asInt()).isGreaterThanOrEqualTo(atLeast);
    });
    return seen[0];
  }

  private String status(String org, String controlId) {
    var report = get("/orgs/" + org + "/governance/compliance", as("reader", org, "viewer")).body();
    for (var control : report.get("controls")) {
      if (control.get("controlId").asText().equals(controlId)) return control.get("status").asText();
    }
    throw new AssertionError("no standing for " + controlId);
  }

  private static Map<String, Object> reviewer(boolean rehearsal) {
    return Map.of("rehearsal", rehearsal);
  }

  private static Map<String, Object> decision(String action, String rationale, boolean rehearsal) {
    return Map.of("reviewer", reviewer(rehearsal), "action", action, "rationale", rationale);
  }

  // ---- tests

  @Test
  public void anApprovalWaitsOnAHumanEventAndLeavesEvidenceOfWhoDecided() throws Exception {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    var id = requestApproval(org, a, key);

    var open = event(org, id);
    assertThat(open.get("state").asText()).isEqualTo("OPEN");
    assertThat(open.get("pending").asBoolean()).isTrue();
    assertThat(open.get("control").get("rowId").asText()).isEqualTo(Controls.ACTION_APPROVAL);
    assertThat(open.get("control").get("corpusRef").asText()).isEqualTo("aws-agentic-scoping.scope2-human-approval");
    assertThat(open.get("mode").asText()).isEqualTo("HITL");
    assertThat(open.get("ask").get("question").asText()).contains("record.update");
    assertThat(open.get("routing").get("roles")).extracting(JsonNode::asText).containsExactly("approver");
    assertThat(open.get("routing").get("independentOf")).extracting(JsonNode::asText).containsExactly("alice");
    assertThat(open.get("subject").get("detail").get("payload").asText()).isEqualTo("payload-" + key);
    assertThat(open.get("sla").get("state").asText()).isEqualTo("running");
    assertThat(open.get("rationaleRequired").asBoolean()).isTrue();
    assertThat(ledgerStatus(org, a, key)).isEqualTo("AWAITING_APPROVAL");

    var bob = as("bob", org, "approver");
    var claimed = post("/oversight/events/" + id + "/claim", bob, Map.of());
    assertThat(claimed.status()).isEqualTo(200);
    assertThat(claimed.body().get("state").asText()).isEqualTo("CLAIMED");
    assertThat(claimed.body().get("holder").get("subject").asText()).isEqualTo("bob");

    var decided = post("/oversight/events/" + id + "/decision", bob, decision("APPROVE", "the payload is routine", false));
    assertThat(decided.status()).isEqualTo(200);
    assertThat(decided.body().get("decisions").get(0).get("authority").asText()).contains("approver").contains("test-idp");
    assertThat(decided.body().get("decisions").get(0).get("belowMinimum").asBoolean()).isTrue();

    awaitLedger(org, a, key, "SUCCEEDED");
    awaitEventState(org, id, "APPLIED");

    var approvals = awaitEvidence(org, Controls.ACTION_APPROVAL, 1);
    var row = approvals.get("evidence").get(0);
    assertThat(row.get("eventType").asText()).isEqualTo("HumanDecisionRecorded");
    assertThat(row.get("evaluationMode").asText()).isEqualTo("ENFORCING");
    assertThat(row.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(row.get("actor").asText()).isEqualTo("bob");
    assertThat(row.get("authority").asText()).contains("approver");
    assertThat(row.get("flagged").asBoolean()).isTrue();
    assertThat(row.get("verdict").asText()).isEqualTo("upheld");
    assertThat(row.get("bundleDigest").asText()).isEqualTo(ControlCatalog.current().digest());

    var rushed = awaitEvidence(org, Controls.RUSHED_DECISIONS, 1).get("evidence").get(0);
    assertThat(rushed.get("decision").asText()).isEqualTo("FAIL");
    assertThat(rushed.get("verdict").asText()).isEqualTo("breached");

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      assertThat(status(org, Controls.ACTION_APPROVAL)).isEqualTo("SATISFIED");
      assertThat(status(org, Controls.RUSHED_DECISIONS)).isEqualTo("FAILING");
    });
    assertThat(status(org, Controls.EGRESS)).isEqualTo("NO_EVIDENCE");
  }

  @Test
  public void thePersonWhoRequestedAnActionCannotApproveIt() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    var id = requestApproval(org, a, key);
    var alice = as("alice", org, "operator", "approver");

    assertThat(post("/oversight/events/" + id + "/claim", alice, Map.of()).status()).isEqualTo(200);
    var refused = post("/oversight/events/" + id + "/decision", alice, decision("APPROVE", "mine", false));

    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.body().get("error").asText()).contains("proposed");
    assertThat(ledgerStatus(org, a, key)).isEqualTo("AWAITING_APPROVAL");
  }

  @Test
  public void aDecisionNeedsAClaimARationaleAndAnAllowedAction() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));
    var bob = as("bob", org, "approver");

    var unclaimed = post("/oversight/events/" + id + "/decision", bob, decision("APPROVE", "fine", false));
    assertThat(unclaimed.status()).isEqualTo(409);
    assertThat(unclaimed.body().get("state").asText()).isEqualTo("OPEN");

    post("/oversight/events/" + id + "/claim", bob, Map.of());
    var noRationale = post("/oversight/events/" + id + "/decision", bob, decision("APPROVE", "  ", false));
    assertThat(noRationale.status()).isEqualTo(422);
    assertThat(noRationale.body().get("errors").get(0).asText()).contains("rationale");
    var notAllowed = post("/oversight/events/" + id + "/decision", bob, decision("HALT", "stop", false));
    assertThat(notAllowed.status()).isEqualTo(422);
    var notACheck = post("/oversight/events/" + id + "/decision", bob,
        Map.of("action", "APPROVE", "rationale", "ok", "checks", List.of("something invented")));
    assertThat(notACheck.status()).isEqualTo(422);
    assertThat(event(org, id).get("pending").asBoolean()).isTrue();
  }

  @Test
  public void onlyOneReviewerHoldsAnEventAndOnlyAnApproverMayClaimIt() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));

    assertThat(post("/oversight/events/" + id + "/claim", as("bob", org, "approver"), Map.of()).status()).isEqualTo(200);
    var second = post("/oversight/events/" + id + "/claim", as("carol", org, "approver"), Map.of());
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().get("state").asText()).isEqualTo("CLAIMED");

    assertThat(post("/oversight/events/" + id + "/release", as("carol", org, "approver"), Map.of()).status()).isEqualTo(403);
    assertThat(post("/oversight/events/" + id + "/release", as("bob", org, "approver"), Map.of()).status()).isEqualTo(200);
    assertThat(event(org, id).get("state").asText()).isEqualTo("OPEN");

    var reviewerOnly = post("/oversight/events/" + id + "/claim", as("rita", org, "reviewer"), Map.of());
    assertThat(reviewerOnly.status()).isEqualTo(403);
    assertThat(reviewerOnly.body().get("error").asText()).contains("approver");
  }

  @Test
  public void aRehearsalIsRecordedButReleasesNothing() throws Exception {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    var id = requestApproval(org, a, key);
    var bob = as("bob", org, "approver");

    var rehearsed = post("/oversight/events/" + id + "/decision", bob, decision("APPROVE", "just looking", true));
    assertThat(rehearsed.status()).isEqualTo(200);
    assertThat(rehearsed.body().get("state").asText()).isEqualTo("OPEN");
    assertThat(rehearsed.body().get("decisions")).isEmpty();
    assertThat(rehearsed.body().get("history").get(rehearsed.body().get("history").size() - 1).get("note").asText())
        .contains("rehearsal");

    var recorded = awaitEvidence(org, Controls.ACTION_APPROVAL, 1).get("evidence").get(0);
    assertThat(recorded.get("evaluationMode").asText()).isEqualTo("TEST");
    assertThat(recorded.get("verdict").asText()).isEqualTo("inconclusive");
    Thread.sleep(1500);
    assertThat(ledgerStatus(org, a, key)).isEqualTo("AWAITING_APPROVAL");
    assertThat(status(org, Controls.ACTION_APPROVAL)).isEqualTo("NO_EVIDENCE");
  }

  @Test
  public void anEventIsEscalatedToTheNextRoleAndSentBackToItsProposer() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));
    var bob = as("bob", org, "approver");
    var root = as("root", org, "admin");

    var escalated = post("/oversight/events/" + id + "/escalate", bob, Map.of("reason", "needs an admin"));
    assertThat(escalated.status()).isEqualTo(200);
    assertThat(escalated.body().get("state").asText()).isEqualTo("ESCALATED");
    assertThat(escalated.body().get("routing").get("roles")).extracting(JsonNode::asText).containsExactly("admin");
    assertThat(post("/oversight/events/" + id + "/claim", bob, Map.of()).status()).isEqualTo(403);

    assertThat(post("/oversight/events/" + id + "/claim", root, Map.of()).status()).isEqualTo(200);
    var sent = post("/oversight/events/" + id + "/send-back", root, Map.of("requested", "which record?"));
    assertThat(sent.status()).isEqualTo(200);
    assertThat(sent.body().get("state").asText()).isEqualTo("CHANGES_REQUESTED");

    assertThat(post("/oversight/events/" + id + "/resubmit", bob, Map.of()).status()).isEqualTo(403);
    var resubmitted = post("/oversight/events/" + id + "/resubmit", as("alice", org, "operator"), Map.of());
    assertThat(resubmitted.status()).isEqualTo(200);
    assertThat(resubmitted.body().get("state").asText()).isEqualTo("ESCALATED");
  }

  @Test
  public void withdrawingARequestRejectsTheAction() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    var id = requestApproval(org, a, key);

    assertThat(post("/oversight/events/" + id + "/withdraw", as("bob", org, "approver"), Map.of("reason", "x")).status())
        .isEqualTo(403);
    var withdrawn = post("/oversight/events/" + id + "/withdraw", as("alice", org, "operator"), Map.of("reason", "wrong record"));

    assertThat(withdrawn.status()).isEqualTo(200);
    assertThat(withdrawn.body().get("state").asText()).isEqualTo("WITHDRAWN");
    awaitLedger(org, a, key, "REJECTED");
  }

  @Test
  public void anEventThatRunsOutOfTimeTimesOutWithTheControlsDefaultAndIsRecorded() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));

    var answer = componentClient.forEventSourcedEntity(id).method(HumanEventEntity::expire)
        .invoke(new HumanEventEntity.Expire(System.currentTimeMillis() + 25L * 3_600_000));

    assertThat(answer.refusal()).isNull();
    var timedOut = event(org, id);
    assertThat(timedOut.get("state").asText()).isEqualTo("TIMED_OUT");
    assertThat(timedOut.get("outcome").get("action").asText()).isEqualTo("DENY");
    assertThat(timedOut.get("outcome").get("slaOutcome").asText()).isEqualTo("TIMED_OUT");
    var row = awaitEvidence(org, Controls.ACTION_APPROVAL, 1).get("evidence").get(0);
    assertThat(row.get("eventType").asText()).isEqualTo("HumanGateTimedOut");
    assertThat(row.get("decision").asText()).isEqualTo("TIMEOUT");
    assertThat(row.get("runtimeConsequence").asText()).isEqualTo("BLOCKED");
  }

  @Test
  public void changesPeopleAuthorizeAreDecidedEventsWithTheirRationale() {
    var org = newOrg();
    var alice = as("alice", org, "operator", "approver");
    var base = "/orgs/" + org + "/assistants/asst-1";
    post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("watch claims"));
    var releaseId = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK))
        .body().get("releaseId").asText();

    var noRationale = post(base + "/releases/" + releaseId + "/approve", alice, new ContextLayerEndpoint.DecisionRequest(" "));
    assertThat(noRationale.status()).isEqualTo(422);

    var notOwner = post(base + "/releases/" + releaseId + "/approve", as("mallory", org, "approver"),
        new ContextLayerEndpoint.DecisionRequest("looks fine"));
    assertThat(notOwner.status()).isEqualTo(400);

    assertThat(post(base + "/releases/" + releaseId + "/approve", alice,
        new ContextLayerEndpoint.DecisionRequest("only annotates")).status()).isEqualTo(200);

    var rows = awaitEvidence(org, Controls.RELEASE_APPROVAL, 1).get("evidence");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("actor").asText()).isEqualTo("alice");
    assertThat(rows.get(0).get("reasonCode").asText()).isEqualTo("reviewed");
    assertThat(rows.get(0).get("authority").asText()).contains("operator").contains("approver");
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(status(org, Controls.RELEASE_APPROVAL)).isEqualTo("SATISFIED"));
  }

  @Test
  public void aPolicyRefusalIsEvidenceAndAShadowRunProvesNothing() {
    var blockedOrg = newOrg();
    var draft = assistant(blockedOrg, AssistantStage.DRAFT);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    post("/orgs/" + blockedOrg + "/assistants/" + draft + "/actions/" + key, as("alice", blockedOrg, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));
    awaitLedger(blockedOrg, draft, key, "DENIED");

    var blocked = awaitEvidence(blockedOrg, Controls.ACTION_POLICY, 1).get("evidence").get(0);
    assertThat(blocked.get("decision").asText()).isEqualTo("BLOCK");
    assertThat(blocked.get("runtimeConsequence").asText()).isEqualTo("BLOCKED");
    assertThat(blocked.get("evaluationMode").asText()).isEqualTo("ENFORCING");
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(status(blockedOrg, Controls.ACTION_POLICY)).isEqualTo("SATISFIED"));

    var shadowOrg = newOrg();
    var shadow = assistant(shadowOrg, AssistantStage.SHADOW);
    var shadowKey = "k-" + UUID.randomUUID().toString().substring(0, 6);
    post("/orgs/" + shadowOrg + "/assistants/" + shadow + "/actions/" + shadowKey, as("alice", shadowOrg, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));
    awaitLedger(shadowOrg, shadow, shadowKey, "WOULD_DO");

    var observed = awaitEvidence(shadowOrg, Controls.ACTION_POLICY, 1).get("evidence").get(0);
    assertThat(observed.get("evaluationMode").asText()).isEqualTo("SHADOW");
    assertThat(observed.get("verdict").asText()).isEqualTo("inconclusive");
    assertThat(status(shadowOrg, Controls.ACTION_POLICY)).isEqualTo("NO_EVIDENCE");
  }

  @Test
  public void evidenceBelongsToItsOrganization() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.DRAFT);
    post("/orgs/" + org + "/assistants/" + a + "/actions/k1", as("alice", org, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));
    awaitEvidence(org, Controls.ACTION_POLICY, 1);

    var other = newOrg();

    assertThat(evidence(other, Controls.ACTION_POLICY).get("count").asInt()).isZero();
  }

  @Test
  public void anAdminCanWaiveAControlAndLiftTheWaiver() {
    var org = newOrg();
    var admin = as("root", org, "admin");
    var path = "/orgs/" + org + "/governance/controls/" + Controls.EGRESS + "/waiver";
    assertThat(status(org, Controls.EGRESS)).isEqualTo("NO_EVIDENCE");

    assertThat(post(path, admin, Map.of("reason", " ")).status()).isEqualTo(400);
    assertThat(post(path, admin, Map.of("reason", "internal hosts only", "until", "2001-01-01T00:00:00Z")).status())
        .isEqualTo(400);
    var waived = post(path, admin, Map.of("reason", "internal hosts only"));
    assertThat(waived.status()).isEqualTo(200);
    assertThat(waived.body().get("waived").asBoolean()).isTrue();
    assertThat(status(org, Controls.EGRESS)).isEqualTo("WAIVED");
    assertThat(post("/orgs/" + org + "/governance/controls/custom-nope/waiver", admin, Map.of("reason", "x")).status())
        .isEqualTo(404);

    assertThat(post(path + "/lift", admin, Map.of()).status()).isEqualTo(200);
    assertThat(status(org, Controls.EGRESS)).isEqualTo("NO_EVIDENCE");
    var trail = awaitEvidence(org, Controls.EGRESS, 2).get("evidence");
    assertThat(trail).extracting(n -> n.get("eventType").asText()).containsExactlyInAnyOrder("ControlWaived", "ControlWaiverLifted");
    assertThat(trail).allSatisfy(n -> assertThat(n.get("evaluationMode").asText()).isEqualTo("ADVISORY"));
  }

  @Test
  public void theCatalogIsServedWithItsDigest() {
    var org = newOrg();

    var catalog = get("/orgs/" + org + "/governance/controls", as("reader", org, "viewer")).body();

    assertThat(catalog.get("digest").asText()).isEqualTo(ControlCatalog.current().digest());
    assertThat(catalog.get("controls")).hasSameSizeAs(ControlCatalog.current().controls());
    var approval = catalog.get("controls").findParents("rowId").stream()
        .filter(n -> n.get("rowId").asText().equals(Controls.ACTION_APPROVAL)).findFirst().orElseThrow();
    assertThat(approval.get("human").get("minimumReviewSeconds").asInt()).isEqualTo(10);
  }

  @Test
  public void anEventOfAnotherOrganizationIsNotFound() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));
    var other = newOrg();

    assertThat(get("/oversight/events/" + id, as("bob", other, "approver")).status()).isEqualTo(404);
    assertThat(post("/oversight/events/" + id + "/claim", as("bob", other, "approver"), Map.of()).status()).isEqualTo(404);
    assertThat(get("/oversight/events?state=pending", as("bob", other, "approver")).body().get("events")).isEmpty();
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/oversight/events?state=pending&routable=me", as("bob", org, "approver")).body().get("events")).hasSize(1));
    assertThat(get("/oversight/events?state=pending&routable=me", as("rita", org, "reviewer")).body().get("events")).isEmpty();
  }

  @Test
  public void optionalContractRoutesWeDoNotOfferAnswerNotImplemented() {
    var org = newOrg();
    var admin = as("root", org, "admin");

    assertThat(post("/oversight/flags", admin, Map.of("note", "x")).status()).isEqualTo(501);
    assertThat(post("/oversight/events/x/reopen", admin, Map.of()).status()).isEqualTo(501);
    assertThat(get("/oversight/halts", admin).body().get("halts")).isEmpty();
    assertThat(get("/oversight/standing", admin).body().get("standing")).isEmpty();
  }

  // ---- identity review as a queue

  /** Two records that match on name and birth date only: not certain enough to join without a person. */
  private void ingestPair(String org, String thirdRecord) {
    var op = as("olga", org, "operator");
    var attrs = Map.of("name", "Sam Ortiz", "dob", "1990-05-05");
    assertThat(post("/orgs/" + org + "/records", op,
        new ContextLayerEndpoint.IngestRequest("Client", "crm", "c1", attrs)).status()).isEqualTo(200);
    assertThat(post("/orgs/" + org + "/records", op,
        new ContextLayerEndpoint.IngestRequest("Claim", "claims", "k1", attrs)).status()).isEqualTo(200);
    if (thirdRecord != null) {
      assertThat(post("/orgs/" + org + "/records", op,
          new ContextLayerEndpoint.IngestRequest("Account", "wealth", thirdRecord, attrs)).status()).isEqualTo(200);
    }
  }

  private JsonNode identityEvents(String org, String state) {
    var events = get("/oversight/events?subject=identity&state=" + state, as("reader", org, "viewer")).body().get("events");
    var out = JsonSupport.getObjectMapper().createArrayNode();
    for (var e : events) if (e.get("control").get("rowId").asText().equals(Controls.IDENTITY_REVIEW)) out.add(e);
    return out;
  }

  private String awaitIdentityEvent(String org) {
    var id = new String[1];
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var open = identityEvents(org, "pending");
      assertThat(open).hasSize(1);
      id[0] = open.get(0).get("id").asText();
    });
    return id[0];
  }

  @Test
  public void aClusterNeedingReviewRaisesAnEventThatNeverShowsTheMatchedText() {
    var org = newOrg();
    ingestPair(org, null);

    var id = awaitIdentityEvent(org);
    var e = event(org, id);

    assertThat(e.get("state").asText()).isEqualTo("OPEN");
    assertThat(e.get("ask").get("question").asText()).contains("one entity");
    assertThat(e.get("routing").get("roles")).extracting(JsonNode::asText).containsExactly("reviewer");
    assertThat(e.get("minimumReviewSeconds").asInt()).isEqualTo(5);
    assertThat(e.toString()).doesNotContain("sam ortiz").doesNotContain("1990-05-05");
  }

  @Test
  public void aReviewerClaimsDecidesAndTheLinkIsAppliedAndEvidenced() {
    var org = newOrg();
    ingestPair(org, null);
    var id = awaitIdentityEvent(org);
    var rita = as("rita", org, "reviewer");

    assertThat(post("/oversight/events/" + id + "/claim", as("bob", org, "approver"), Map.of()).status()).isEqualTo(403);
    assertThat(post("/oversight/events/" + id + "/claim", rita, Map.of()).status()).isEqualTo(200);
    var decided = post("/oversight/events/" + id + "/decision", rita, decision("APPROVE", "same birth date and employer", false));
    assertThat(decided.status()).isEqualTo(200);

    awaitEventState(org, id, "APPLIED");
    assertThat(event(org, id).get("outcome").get("applied").asText()).contains("confirmed");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/identity/review", rita).body().get("items")).isEmpty());

    var row = awaitEvidence(org, Controls.IDENTITY_REVIEW, 1).get("evidence").get(0);
    assertThat(row.get("actor").asText()).isEqualTo("rita");
    assertThat(row.get("flagged").asBoolean()).isTrue();
    assertThat(awaitEvidence(org, Controls.RUSHED_DECISIONS, 1).get("evidence").get(0).get("decision").asText())
        .isEqualTo("FAIL");
  }

  @Test
  public void aDeniedReviewKeepsTheRecordsApart() {
    var org = newOrg();
    ingestPair(org, null);
    var id = awaitIdentityEvent(org);
    var rita = as("rita", org, "reviewer");

    post("/oversight/events/" + id + "/claim", rita, Map.of());
    assertThat(post("/oversight/events/" + id + "/decision", rita, decision("DENY", "different employers", false)).status())
        .isEqualTo(200);

    awaitEventState(org, id, "APPLIED");
    assertThat(event(org, id).get("outcome").get("applied").asText()).contains("rejected");
  }

  @Test
  public void aRecordJoiningTheClusterClosesTheOldQuestionAndAsksANewOne() {
    var org = newOrg();
    ingestPair(org, null);
    var first = awaitIdentityEvent(org);

    ingestRecordTo(org, "w1");

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      assertThat(event(org, first).get("state").asText()).isEqualTo("WITHDRAWN");
      var open = identityEvents(org, "pending");
      assertThat(open).hasSize(1);
      assertThat(open.get(0).get("id").asText()).isNotEqualTo(first);
    });
  }

  private void ingestRecordTo(String org, String recordId) {
    assertThat(post("/orgs/" + org + "/records", as("olga", org, "operator"),
        new ContextLayerEndpoint.IngestRequest("Account", "wealth", recordId,
            Map.of("name", "Sam Ortiz", "dob", "1990-05-05"))).status()).isEqualTo(200);
  }

  @Test
  public void aReviewNobodyAnswersTimesOutAndTheLinkStaysUnconfirmed() {
    var org = newOrg();
    ingestPair(org, null);
    var id = awaitIdentityEvent(org);

    var answer = componentClient.forEventSourcedEntity(id).method(HumanEventEntity::expire)
        .invoke(new HumanEventEntity.Expire(System.currentTimeMillis() + 8L * 24 * 3_600_000));

    assertThat(answer.refusal()).isNull();
    assertThat(event(org, id).get("state").asText()).isEqualTo("TIMED_OUT");
    assertThat(event(org, id).get("outcome").get("action").asText()).isEmpty();
    assertThat(get("/orgs/" + org + "/identity/review", as("rita", org, "reviewer")).body().get("items")).hasSize(1);
  }

  @Test
  public void aTokenClaimingToBeTheSystemIsNotSignedIn() {
    var org = newOrg();

    assertThat(get("/orgs/" + org + "/assistants", as("system:identity", org, "admin")).status()).isEqualTo(401);
  }

  @Test
  public void aReviewerWhoOpensTheClusterAndTakesTheTimeIsNotFlagged() throws Exception {
    var org = newOrg();
    ingestPair(org, null);
    awaitIdentityEvent(org);
    var rita = as("rita", org, "reviewer");
    var cluster = get("/orgs/" + org + "/identity/review", rita);
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/identity/review", rita).body().get("items")).hasSize(1));
    var item = get("/orgs/" + org + "/identity/review", rita).body().get("items").get(0);
    var ref = new ContextLayerEndpoint.ClusterRef(item.get("rule").asText(), item.get("key").asText(), "same person");

    var opened = post("/orgs/" + org + "/identity/open", rita, ref);
    assertThat(opened.status()).isEqualTo(200);
    assertThat(opened.body().get("minimumReviewSeconds").asInt()).isEqualTo(5);
    assertThat(post("/orgs/" + org + "/identity/open", as("rex", org, "reviewer"), ref).status()).isEqualTo(409);

    Thread.sleep(6000);
    assertThat(post("/orgs/" + org + "/identity/confirm", rita, ref).status()).isEqualTo(200);

    var row = awaitEvidence(org, Controls.IDENTITY_REVIEW, 1).get("evidence").get(0);
    assertThat(row.get("flagged").asBoolean()).isFalse();
    assertThat(awaitEvidence(org, Controls.RUSHED_DECISIONS, 1).get("evidence").get(0).get("decision").asText())
        .isEqualTo("PASS");
    assertThat(cluster.status()).isEqualTo(200);
  }

  @Test
  public void aReviewerCanGiveAnEventMoreTimeOnce() {
    var org = newOrg();
    ingestPair(org, null);
    var id = awaitIdentityEvent(org);
    var rita = as("rita", org, "reviewer");
    var due = event(org, id).get("sla").get("dueAt").asText();

    var extended = post("/oversight/events/" + id + "/extend", rita, Map.of("extra", "PT48H", "reason", "waiting on HR"));

    assertThat(extended.status()).isEqualTo(200);
    assertThat(java.time.Instant.parse(extended.body().get("sla").get("dueAt").asText()))
        .isEqualTo(java.time.Instant.parse(due).plus(java.time.Duration.ofHours(48)));
    assertThat(extended.body().get("slaExtensions")).hasSize(1);
    assertThat(post("/oversight/events/" + id + "/extend", rita, Map.of("extra", "PT1H", "reason", "more")).status()).isEqualTo(409);
    assertThat(post("/oversight/events/" + id + "/extend", rita, Map.of("extra", "soon", "reason", "x")).status()).isEqualTo(422);
    assertThat(post("/oversight/events/" + id + "/extend", as("olga", org, "operator"), Map.of("extra", "PT1H", "reason", "x")).status())
        .isEqualTo(403);
    var tooEarly = componentClient.forEventSourcedEntity(id).method(HumanEventEntity::expire)
        .invoke(new HumanEventEntity.Expire(java.time.Instant.parse(due).toEpochMilli() + 1000));
    assertThat(tooEarly.refusal()).isNotNull();
  }

  // ---- halting an assistant

  /** The halt scope of an assistant; the list comes from a view, so it can trail the assistant's creation. */
  private JsonNode haltOf(String org, String assistant) {
    var found = new JsonNode[1];
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      for (var h : get("/oversight/halts", as("reader", org, "viewer")).body().get("halts")) {
        if (h.get("target").asText().equals(assistant)) found[0] = h;
      }
      assertThat(found[0]).as("halt scope for " + assistant).isNotNull();
    });
    return found[0];
  }

  private void awaitHalted(String org, String assistant, boolean halted) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(haltOf(org, assistant).get("halted").asBoolean()).isEqualTo(halted));
  }

  private String requestHalt(String org, String assistant, boolean lift) {
    var raised = post("/oversight/halts", as("rita", org, "reviewer"),
        Map.of("scope", "assistant", "target", assistant, "lift", lift, "reason", lift ? "cause fixed" : "it is sending to the wrong client"));
    assertThat(raised.status()).isEqualTo(200);
    assertThat(raised.body().get("state").asText()).isEqualTo("OPEN");
    return raised.body().get("id").asText();
  }

  @Test
  public void oneApproverHaltsAnAssistantAndItsActionsAreRefusedUntilTwoRestartIt() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    assertThat(haltOf(org, a).get("halted").asBoolean()).isFalse();

    var halt = requestHalt(org, a, false);
    assertThat(event(org, halt).get("control").get("rowId").asText()).isEqualTo(Controls.HALT);
    assertThat(event(org, halt).get("mode").asText()).isEqualTo("HALT");
    assertThat(post("/oversight/events/" + halt + "/decision", as("bob", org, "approver"),
        decision("APPROVE", "stop it now", false)).status()).isEqualTo(200);
    awaitHalted(org, a, true);
    assertThat(haltOf(org, a).get("by").asText()).isEqualTo("bob");
    assertThat(haltOf(org, a).get("eventId").asText()).isEqualTo(halt);

    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    post("/orgs/" + org + "/assistants/" + a + "/actions/" + key, as("olga", org, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));
    awaitLedger(org, a, key, "DENIED");
    assertThat(get("/orgs/" + org + "/assistants/" + a + "/actions/" + key, as("reader", org, "viewer")).body().get("reason").asText())
        .isEqualTo("playbook is halted");

    assertThat(post("/oversight/halts", as("rita", org, "reviewer"),
        Map.of("scope", "assistant", "target", a, "reason", "again")).status()).isEqualTo(409);

    var restart = requestHalt(org, a, true);
    assertThat(event(org, restart).get("control").get("rowId").asText()).isEqualTo(Controls.RESTART);
    var first = post("/oversight/events/" + restart + "/decision", as("bob", org, "approver"), decision("APPROVE", "fixed", false));
    assertThat(first.body().get("state").asText()).isEqualTo("AWAITING_SECOND");
    assertThat(post("/oversight/events/" + restart + "/decision", as("bob", org, "approver"), decision("APPROVE", "again", false)).status())
        .isEqualTo(409);
    assertThat(haltOf(org, a).get("halted").asBoolean()).isTrue();
    assertThat(post("/oversight/events/" + restart + "/decision", as("carol", org, "approver"), decision("APPROVE", "agreed", false)).status())
        .isEqualTo(200);
    awaitHalted(org, a, false);

    var again = "k-" + UUID.randomUUID().toString().substring(0, 6);
    post("/orgs/" + org + "/assistants/" + a + "/actions/" + again, as("olga", org, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));
    awaitLedger(org, a, again, "SUCCEEDED");
    var evidence = awaitEvidence(org, Controls.ACTION_POLICY, 1).get("evidence");
    assertThat(evidence).extracting(n -> n.get("reasonCode").asText()).contains("playbook is halted");
  }

  @Test
  public void aHaltCannotBeRequestedForNothingOrForTheWrongScopeAndARehearsalHaltsNothing() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var rita = as("rita", org, "reviewer");

    assertThat(post("/oversight/halts", rita, Map.of("scope", "service", "target", a, "reason", "x")).status()).isEqualTo(422);
    assertThat(post("/oversight/halts", rita, Map.of("scope", "assistant", "target", a, "reason", " ")).status()).isEqualTo(422);
    assertThat(post("/oversight/halts", rita, Map.of("scope", "assistant", "target", "nobody", "reason", "x")).status()).isEqualTo(404);
    assertThat(post("/oversight/halts", rita, Map.of("scope", "assistant", "target", a, "lift", true, "reason", "x")).status())
        .isEqualTo(409);
    assertThat(post("/oversight/halts", as("olga", org, "operator"),
        Map.of("scope", "assistant", "target", a, "reason", "x")).status()).isEqualTo(403);

    var rehearsal = post("/oversight/halts", rita, Map.of("scope", "assistant", "target", a, "reason", "practice",
        "reviewer", Map.of("rehearsal", true)));
    assertThat(rehearsal.status()).isEqualTo(200);
    assertThat(get("/oversight/events?state=pending&subject=system", as("bob", org, "approver")).body().get("events")).isEmpty();
    assertThat(haltOf(org, a).get("halted").asBoolean()).isFalse();
  }

  @Test
  public void anActionApprovedAfterAHaltDoesNotRun() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var key = "k-" + UUID.randomUUID().toString().substring(0, 6);
    var approval = requestApproval(org, a, key);
    var bob = as("bob", org, "approver");

    var halt = requestHalt(org, a, false);
    post("/oversight/events/" + halt + "/decision", bob, decision("APPROVE", "stop it", false));
    awaitHalted(org, a, true);
    post("/oversight/events/" + approval + "/claim", bob, Map.of());
    assertThat(post("/oversight/events/" + approval + "/decision", bob, decision("APPROVE", "routine", false)).status()).isEqualTo(200);

    awaitLedger(org, a, key, "REJECTED");
    awaitEventState(org, approval, "APPLIED");
    assertThat(event(org, approval).get("outcome").get("applied").asText()).contains("halted");
  }

  @Test
  public void aDeniedHaltLeavesTheAssistantRunning() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.LIVE);
    var halt = requestHalt(org, a, false);

    assertThat(post("/oversight/events/" + halt + "/decision", as("bob", org, "approver"), decision("DENY", "false alarm", false)).status())
        .isEqualTo(200);

    awaitEventState(org, halt, "APPLIED");
    assertThat(haltOf(org, a).get("halted").asBoolean()).isFalse();
  }

  // ---- telling the people an event waits on

  private void route(String org, String name) {
    assertThat(wire().POST("/orgs/" + org + "/routes/" + name.replace(":", "%3A"))
        .addHeader("Authorization", as("root", org, "admin"))
        .withRequestBody(new io.akka.contextlayer.api.NotificationEndpoint.RouteRequest("http://localhost:9/hook", "json", null))
        .invoke().status().intValue()).isEqualTo(200);
  }

  private List<RecordingNotifier.Sent> sentFor(String org, String route) {
    return NOTIFIER.sent.stream().filter(s -> s.message().organization().equals(org) && s.message().route().equals(route)).toList();
  }

  @Test
  public void anApprovalIsAnnouncedToItsRoleAndAgainWhenItIsEscalated() {
    var org = newOrg();
    route(org, "role:approver");
    route(org, "role:admin");
    var a = assistant(org, AssistantStage.LIVE);
    var id = requestApproval(org, a, "k-" + UUID.randomUUID().toString().substring(0, 6));

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(sentFor(org, "role:approver")).hasSize(1));
    var first = sentFor(org, "role:approver").get(0).message();
    assertThat(first.type()).isEqualTo("approval.requested");
    assertThat(first.reference()).containsEntry("eventId", id).containsEntry("control", Controls.ACTION_APPROVAL);
    assertThat(first.dueAt()).isNotBlank();

    post("/oversight/events/" + id + "/escalate", as("bob", org, "approver"), Map.of("reason", "needs an admin"));

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(sentFor(org, "role:admin")).hasSize(1));
    assertThat(sentFor(org, "role:admin").get(0).message().type()).isEqualTo("approval.escalated");
  }

  @Test
  public void changesDecidedInOneCallAreNotAnnouncedBecauseNobodyIsNeeded() throws Exception {
    var org = newOrg();
    route(org, "role:approver");

    assistant(org, AssistantStage.LIVE);  // approves a release and moves a stage

    Thread.sleep(1500);
    assertThat(sentFor(org, "role:approver")).isEmpty();
  }
}

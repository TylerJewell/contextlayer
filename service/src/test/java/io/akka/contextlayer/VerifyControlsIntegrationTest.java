package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.DomainEndpoint;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.governance.Controls;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Each control records its verdict in its mechanism's shape and under its own id: the release's gates, a guardrail's
 * refusal and the passes before it, and a human control's decisions and the requests its rules turn down.
 */
public class VerifyControlsIntegrationTest extends TestKitSupport {

  private Wire wire() {
    return new Wire(httpClient);
  }

  private static final String PLAYBOOK =
      "{\"steps\":[{\"id\":\"note\",\"type\":\"annotate\",\"text\":\"ok\",\"next\":\"end\"}]}";

  private record Reply(int status, JsonNode body) {}

  private static final ActionExecutor EXECUTOR = (org, type, payload) -> ActionExecutor.Result.ok("done");

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestDependencies.settings().withDependencyProvider(
        TestDependencies.provider(EXECUTOR, TestDependencies.NO_SOURCES, TestDependencies.NO_STORE, new RecordingNotifier()));
  }

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
      try {
        request = request.withRequestBody(ContentTypes.APPLICATION_JSON,
            JsonSupport.getObjectMapper().writeValueAsBytes(body == null ? Map.of() : body));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    var response = request.invoke();
    var text = response.body().utf8String();
    JsonNode parsed;
    try {
      parsed = text.isBlank() ? null : JsonSupport.getObjectMapper().readTree(text);
    } catch (Exception e) {
      parsed = com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }
    return new Reply(response.status().intValue(), parsed);
  }

  private Reply get(String path, String token) {
    return send("GET", path, token, null);
  }

  private Reply post(String path, String token, Object body) {
    return send("POST", path, token, body);
  }

  private JsonNode awaitEvidence(String org, String control, int atLeast) {
    var seen = new JsonNode[1];
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      seen[0] = get("/orgs/" + org + "/governance/evidence?control=" + control, as("reader", org, "viewer")).body();
      assertThat(seen[0].get("count").asInt()).isGreaterThanOrEqualTo(atLeast);
    });
    return seen[0].get("evidence");
  }

  /** An assistant owned by alice, with an approved release, in the given stage. */
  private String assistant(String org, AssistantStage stage) {
    var alice = as("alice", org, "operator", "approver");
    var a = "asst-" + UUID.randomUUID().toString().substring(0, 8);
    var base = "/orgs/" + org + "/assistants/" + a;
    assertThat(post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("watch claims")).status()).isEqualTo(200);
    var releaseId = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK)).body()
        .get("releaseId").asText();
    assertThat(post(base + "/releases/" + releaseId + "/approve", alice,
        new ContextLayerEndpoint.DecisionRequest("the playbook only annotates")).status()).isEqualTo(200);
    if (stage != AssistantStage.DRAFT) {
      assertThat(post(base + "/stage", alice, new ContextLayerEndpoint.StageRequest(stage, "ready for use")).status())
          .isEqualTo(200);
    }
    return a;
  }

  @Test
  public void theReleaseRecordsItsGatesAndConformanceWhenItFirstServesAProject() {
    var org = newOrg();
    get("/orgs/" + org + "/governance/controls", as("reader", org, "viewer"));

    var catalog = awaitEvidence(org, Controls.CATALOG_VALID_AT_STARTUP, 1).get(0);
    assertThat(catalog.get("eventType").asText()).isEqualTo("GateEvaluated");
    assertThat(catalog.get("decision").asText()).isEqualTo("PASS");
    assertThat(catalog.get("boundary").asText()).isEqualTo("AT_DEPLOYMENT");

    // The bundle in force (governance/signed/) holds every control exactly as the release runs it.
    var bundle = awaitEvidence(org, Controls.DRAFT_MATCHES_BUNDLE, 1).get(0);
    assertThat(bundle.get("decision").asText()).isEqualTo("PASS");
    assertThat(bundle.get("verdict").asText()).isEqualTo("upheld");
    var conformance = awaitEvidence(org, Controls.SIGNED_CONTRACT_CONFORMANCE, 1).get(0);
    assertThat(conformance.get("eventType").asText()).isEqualTo("EvaluationRecorded");
    assertThat(conformance.get("decision").asText()).isEqualTo("PASS");
  }

  @Test
  public void aCallerWithoutThePermissionIsRefusedAndTheRefusalRecorded() {
    var org = newOrg();
    var refused = post("/orgs/" + org + "/assistants/a1", as("victor", org, "viewer"),
        new ContextLayerEndpoint.CreateAssistantRequest("x"));
    assertThat(refused.status()).isEqualTo(403);

    var evidence = awaitEvidence(org, Controls.CALLER_AUTHORIZATION, 1);
    assertThat(evidence).anySatisfy(e -> {
      assertThat(e.get("decision").asText()).isEqualTo("BLOCK");
      assertThat(e.get("actor").asText()).isEqualTo("victor");
      assertThat(e.get("boundary").asText()).isEqualTo("BEFORE_TOOL_CALL");
    });
  }

  @Test
  public void aDraftPlaybooksActionIsBlockedByTheStageGateAfterTheEarlierGuardrailsPass() {
    var org = newOrg();
    var a = assistant(org, AssistantStage.DRAFT);
    post("/orgs/" + org + "/assistants/" + a + "/actions/k1", as("alice", org, "operator"),
        new ContextLayerEndpoint.ActionRequest("notify.log", "x"));

    assertThat(awaitEvidence(org, Controls.STAGE_GATED_ACTIONS, 1).get(0).get("decision").asText()).isEqualTo("BLOCK");
    for (var passed : List.of(Controls.HALT_ENFORCEMENT, Controls.LIVE_CONNECTOR_ACTIONS, Controls.NEVER_BY_PLAYBOOK)) {
      assertThat(awaitEvidence(org, passed, 1).get(0).get("decision").asText()).as(passed).isEqualTo("PASS");
    }
  }

  @Test
  public void aReleaseApprovalBySomeoneOtherThanTheOwnerIsRefusedAndRecorded() {
    var org = newOrg();
    var alice = as("alice", org, "operator", "approver");
    var base = "/orgs/" + org + "/assistants/a-" + UUID.randomUUID().toString().substring(0, 6);
    post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("watch claims"));
    var releaseId = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK)).body()
        .get("releaseId").asText();

    var refused = post(base + "/releases/" + releaseId + "/approve", as("bob", org, "approver"),
        new ContextLayerEndpoint.DecisionRequest("looks fine"));
    assertThat(refused.status()).isEqualTo(400);

    var evidence = awaitEvidence(org, Controls.RELEASE_APPROVAL, 1);
    assertThat(evidence).anySatisfy(e -> {
      assertThat(e.get("decision").asText()).isEqualTo("REFUSED");
      assertThat(e.get("actor").asText()).isEqualTo("bob");
    });
    assertThat(awaitEvidence(org, Controls.PLAYBOOK_DEFINITION_BOUNDS, 1).get(0).get("decision").asText()).isEqualTo("PASS");
  }

  @Test
  public void aWaiverNeedsAnEndAndIsAnAdministratorsDecision() {
    var org = newOrg();
    var erin = as("erin", org, "admin");
    var path = "/orgs/" + org + "/governance/controls/" + Controls.EGRESS + "/waiver";

    assertThat(post(path, erin, Map.of("reason", "internal hosts only")).status()).isEqualTo(400);
    var tooLong = Instant.now().plus(400, ChronoUnit.DAYS).toString();
    assertThat(post(path, erin, Map.of("reason", "internal hosts only", "until", tooLong)).status()).isEqualTo(400);
    var until = Instant.now().plus(30, ChronoUnit.DAYS).toString();
    assertThat(post(path, erin, Map.of("reason", "internal hosts only", "until", until)).status()).isEqualTo(200);

    var decision = awaitEvidence(org, Controls.CONTROL_WAIVER, 1).get(0);
    assertThat(decision.get("eventType").asText()).isEqualTo("HumanDecisionRecorded");
    assertThat(decision.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(decision.get("actor").asText()).isEqualTo("erin");
  }

  @Test
  public void changingADataDomainNeedsAReasonAndIsRecorded() {
    var org = newOrg();
    var erin = as("erin", org, "admin");
    var path = "/orgs/" + org + "/domains/claims";

    assertThat(post(path, erin, new DomainEndpoint.DomainRequest("claims", List.of("Claim"), List.of("operator"), List.of()))
        .status()).isEqualTo(422);
    assertThat(post(path, erin, new DomainEndpoint.DomainRequest("claims", List.of("Claim"), List.of("operator"), List.of(),
        "claims handlers read claims")).status()).isEqualTo(200);

    var decision = awaitEvidence(org, Controls.DOMAIN_ADMINISTRATION, 2);
    assertThat(decision).anySatisfy(e -> assertThat(e.get("decision").asText()).isEqualTo("APPROVE"));
    assertThat(decision).anySatisfy(e -> assertThat(e.get("decision").asText()).isEqualTo("REFUSED"));
  }
}

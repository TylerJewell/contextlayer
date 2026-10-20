package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.application.ActionWorkflow;
import io.akka.contextlayer.application.CallRateLimiter;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.HttpActionExecutor;
import io.akka.contextlayer.application.HttpSourceCaller;
import io.akka.contextlayer.application.LoggingActionExecutor;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.RiskClass;
import io.akka.contextlayer.domain.ScopedId;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An approved action reaching a real system over HTTP, and what each kind of answer means: applied,
 * refused, or not known. The target is a local server that records what it is sent.
 */
public class ActionExecutionIntegrationTest extends TestKitSupport {
private Wire wire() {    return new Wire(httpClient);  }

  private static final String PLAYBOOK =
      "{\"steps\":[{\"id\":\"note\",\"type\":\"annotate\",\"text\":\"ok\",\"next\":\"end\"}]}";

  private static MockTarget target;

  static {
    try {
      target = new MockTarget();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopTarget() {
    target.close();
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    var limits = new HttpSourceCaller.Limits(5, 100, 1_000_000, Duration.ofSeconds(1), 1000);
    var caller = new HttpSourceCaller(ref -> Optional.of("tok"), new EgressPolicy(List.of("localhost")),
        new CallRateLimiter(), limits, System::currentTimeMillis);
    var executor = new HttpActionExecutor(caller, Duration.ofSeconds(1), new LoggingActionExecutor());
    return TestDependencies.settings().withDependencyProvider(
        TestDependencies.provider(executor, TestDependencies.NO_SOURCES, TestDependencies.NO_STORE));
  }

  @BeforeEach
  void resetTarget() {
    target.answer(MockTarget.Answer.OK);
    target.received().clear();
  }

  // ---- helpers

  private record Reply(int status, JsonNode body) {}

  private Reply post(String path, String token, Object body) {
    try {
      var response = wire().POST(path).addHeader("Authorization", token)
          .withRequestBody(ContentTypes.APPLICATION_JSON, JsonSupport.getObjectMapper().writeValueAsBytes(body)).invoke();
      var text = response.body().utf8String();
      JsonNode parsed = null;
      try {
        parsed = text.isBlank() ? null : JsonSupport.getObjectMapper().readTree(text);
      } catch (Exception e) {
        // plain-text error
      }
      return new Reply(response.status().intValue(), parsed);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode get(String path, String token) {
    try {
      return JsonSupport.getObjectMapper().readTree(
          wire().GET(path).addHeader("Authorization", token).invoke().body().utf8String());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String org() {
    return "org-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** A live connector with one action, and a live assistant. Returns the assistant id. */
  private String setUp(String org, String sourceRef, RiskClass risk) {
    var ivy = Tokens.bearer("ivy", org, "operator");
    var pack = new ContextLayerEndpoint.PackRequest("crm", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", sourceRef), "vault://crm", List.of(),
        List.of(new ContextLayerEndpoint.PackActionRequest("addNote", "addNote", risk),
            new ContextLayerEndpoint.PackActionRequest("listClaims", "listClaims", risk)));
    var imported = post("/orgs/" + org + "/connectors/crm/versions", ivy, pack);
    assertThat(imported.status()).isEqualTo(200);
    assertThat(post("/orgs/" + org + "/connectors/crm/versions/" + imported.body().get("hash").asText() + "/activate",
        Tokens.bearer("bob", org, "approver"),
        new ContextLayerEndpoint.ActivateRequest(ConnectorPack.Mode.LIVE, "reviewed the operations")).status()).isEqualTo(200);

    var alice = Tokens.bearer("alice", org, "operator", "approver");
    var a = "asst-" + UUID.randomUUID().toString().substring(0, 6);
    var base = "/orgs/" + org + "/assistants/" + a;
    post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("add notes"));
    var release = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK)).body().get("releaseId").asText();
    post(base + "/releases/" + release + "/approve", alice, new ContextLayerEndpoint.DecisionRequest("only annotates"));
    post(base + "/stage", alice, new ContextLayerEndpoint.StageRequest(io.akka.contextlayer.domain.AssistantStage.LIVE, "ready"));
    return a;
  }

  private void request(String org, String assistant, String key, String type, String payload) {
    assertThat(post("/orgs/" + org + "/assistants/" + assistant + "/actions/" + key,
        Tokens.bearer("olga", org, "operator"), new ContextLayerEndpoint.ActionRequest(type, payload)).status()).isEqualTo(200);
  }

  private JsonNode action(String org, String assistant, String key) {
    return get("/orgs/" + org + "/assistants/" + assistant + "/actions/" + key, Tokens.bearer("reader", org, "viewer"));
  }

  private JsonNode awaitStatus(String org, String assistant, String key, String status) {
    var seen = new JsonNode[1];
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      seen[0] = action(org, assistant, key);
      assertThat(seen[0].get("status").asText()).isEqualTo(status);
    });
    return seen[0];
  }

  private static String key() {
    return "k-" + UUID.randomUUID().toString().substring(0, 6);
  }

  // ---- tests

  @Test
  public void anApprovedActionIsSentOnceToTheSystemWithTheCredentialAndAnIdempotencyKey() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"called the client\"}");

    var done = awaitStatus(org, a, k, "SUCCEEDED");
    assertThat(done.get("reason").asText()).contains("HTTP 200");
    assertThat(target.received()).hasSize(1);
    var sent = target.received().get(0);
    assertThat(sent.method()).isEqualTo("POST");
    assertThat(sent.path()).isEqualTo("/claims/CL-1/notes");
    assertThat(sent.authorization()).isEqualTo("Bearer tok");
    assertThat(sent.idempotencyKey()).isEqualTo(ScopedId.of(org, a, k));
    assertThat(JsonSupport.getObjectMapper().valueToTree(Map.of("text", "called the client")).toString()).isEqualTo(sent.body());
  }

  @Test
  public void askingAgainWithTheSameKeySendsNothingMore() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();
    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"once\"}");
    awaitStatus(org, a, k, "SUCCEEDED");

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"once\"}");

    assertThat(target.received()).hasSize(1);
  }

  @Test
  public void aRiskyActionIsSentOnlyAfterApprovalAndThenOnce() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.NEEDS_APPROVAL);
    var k = key();
    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-9\",\"text\":\"waiting\"}");
    awaitStatus(org, a, k, "AWAITING_APPROVAL");
    assertThat(target.received()).isEmpty();

    var id = ActionWorkflow.humanEventId(org, a, k);
    var bob = Tokens.bearer("bob", org, "approver");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(post("/oversight/events/" + id + "/claim", bob, Map.of()).status()).isEqualTo(200));
    assertThat(post("/oversight/events/" + id + "/decision", bob, Map.of("action", "APPROVE", "rationale", "fine")).status())
        .isEqualTo(200);

    awaitStatus(org, a, k, "SUCCEEDED");
    assertThat(target.received()).hasSize(1);
  }

  @Test
  public void aSystemThatRefusesMakesTheActionFailedNotUnknown() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();
    target.answer(MockTarget.Answer.REFUSE);

    request(org, a, k, "crm.addNote", "{\"claimId\":\"NOPE\",\"text\":\"x\"}");

    assertThat(awaitStatus(org, a, k, "FAILED").get("reason").asText()).contains("HTTP 422");
    assertThat(target.received()).hasSize(1);
  }

  @Test
  public void aServerErrorMeansTheOutcomeIsUnknownAndItIsNotRetried() throws Exception {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();
    target.answer(MockTarget.Answer.ERROR);

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"x\"}");

    awaitStatus(org, a, k, "OUTCOME_UNKNOWN");
    Thread.sleep(1500);
    assertThat(target.received()).hasSize(1);
  }

  @Test
  public void aSystemThatNeverAnswersMeansTheOutcomeIsUnknown() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();
    target.answer(MockTarget.Answer.HANG);

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"x\"}");

    awaitStatus(org, a, k, "OUTCOME_UNKNOWN");
    assertThat(target.received()).hasSize(1);
  }

  @Test
  public void aConnectionCutBeforeAnAnswerMeansTheOutcomeIsUnknown() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);
    var k = key();
    target.answer(MockTarget.Answer.DROP);

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"x\"}");

    awaitStatus(org, a, k, "OUTCOME_UNKNOWN");
  }

  @Test
  public void aRequestThatCannotBeFormedSendsNothingAndFails() {
    var org = org();
    var a = setUp(org, target.specUrl(), RiskClass.UNATTENDED);

    var noPathValue = key();
    request(org, a, noPathValue, "crm.addNote", "{\"text\":\"no claim id\"}");
    assertThat(awaitStatus(org, a, noPathValue, "FAILED").get("reason").asText()).contains("claimId");

    var notJson = key();
    request(org, a, notJson, "crm.addNote", "call the client");
    assertThat(awaitStatus(org, a, notJson, "FAILED").get("reason").asText()).contains("not valid JSON");

    var aRead = key();
    request(org, a, aRead, "crm.listClaims", "{}");
    assertThat(awaitStatus(org, a, aRead, "FAILED").get("reason").asText()).contains("must change something");

    assertThat(target.received()).isEmpty();
  }

  @Test
  public void aHostOffTheAllowListIsRefusedBeforeAnythingIsSentAndRecordedAsEvidence() {
    var org = org();
    var a = setUp(org, "http://not-allowed.example:9/openapi.json", RiskClass.UNATTENDED);
    var k = key();

    request(org, a, k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"x\"}");

    assertThat(awaitStatus(org, a, k, "FAILED").get("reason").asText()).endsWith("allowed list of hosts");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var evidence = get("/orgs/" + org + "/governance/evidence?control=custom-cl0009", Tokens.bearer("reader", org, "viewer"));
      assertThat(evidence.get("evidence")).isNotEmpty();
      assertThat(evidence.get("evidence").get(0).get("decision").asText()).isEqualTo("BLOCK");
    });
  }

  @Test
  public void aConnectorThatIsOnlyReadOnlyOffersNoActions() {
    var org = org();
    var ivy = Tokens.bearer("ivy", org, "operator");
    var pack = new ContextLayerEndpoint.PackRequest("crm", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", target.specUrl()), "vault://crm", List.of(),
        List.of(new ContextLayerEndpoint.PackActionRequest("addNote", "addNote", RiskClass.UNATTENDED)));
    var imported = post("/orgs/" + org + "/connectors/crm/versions", ivy, pack);
    post("/orgs/" + org + "/connectors/crm/versions/" + imported.body().get("hash").asText() + "/activate",
        Tokens.bearer("bob", org, "approver"),
        new ContextLayerEndpoint.ActivateRequest(ConnectorPack.Mode.READ_ONLY, "reads only"));
    var alice = Tokens.bearer("alice", org, "operator", "approver");
    var base = "/orgs/" + org + "/assistants/a1";
    post(base, alice, new ContextLayerEndpoint.CreateAssistantRequest("add notes"));
    var release = post(base + "/releases", alice, new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK)).body().get("releaseId").asText();
    post(base + "/releases/" + release + "/approve", alice, new ContextLayerEndpoint.DecisionRequest("ok"));
    post(base + "/stage", alice, new ContextLayerEndpoint.StageRequest(io.akka.contextlayer.domain.AssistantStage.LIVE, "go"));
    var k = key();

    request(org, "a1", k, "crm.addNote", "{\"claimId\":\"CL-1\",\"text\":\"x\"}");

    awaitStatus(org, "a1", k, "DENIED");
    assertThat(target.received()).isEmpty();
  }
}

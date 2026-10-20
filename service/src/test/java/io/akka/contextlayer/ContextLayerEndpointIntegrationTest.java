package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.application.ActionExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The governance flow end to end against a running runtime: staged assistants, releases,
 * approvals, the action ledger, the unknown-outcome path, organization isolation, identity review.
 */
public class ContextLayerEndpointIntegrationTest extends TestKitSupport {
private Wire wire() {    return new Wire(httpClient);  }

  /** Records every call that leaves the platform. Payload "crash" simulates an unknown outcome. */
  static class RecordingExecutor implements ActionExecutor {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public Result perform(String orgId, String actionType, String payload) {
      calls.add(orgId + ":" + actionType + ":" + payload);
      if (payload.contains("crash")) throw new IllegalStateException("connection reset");
      return Result.ok("done");
    }
  }

  private static final RecordingExecutor executor = new RecordingExecutor();

  /** The smallest valid playbook: one note, then done. */
  static final String SIMPLE_PLAYBOOK =
      "{\"steps\":[{\"id\":\"note\",\"type\":\"annotate\",\"text\":\"ok\",\"next\":\"end\"}]}";

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestDependencies.settings().withDependencyProvider(
        TestDependencies.provider(executor, TestDependencies.NO_SOURCES, TestDependencies.NO_STORE));
  }

  private static String id() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private <B> B post(String principal, String path, Object body, Class<B> type) {
    var response = wire().POST(path).addHeader("Authorization", Tokens.admin(principal, path))
        .withRequestBody(body).responseBodyAs(type).invoke();
    assertThat(response.status().isSuccess()).as(path + " -> " + response.status()).isTrue();
    return response.body();
  }

  private int postStatus(String principal, String path, Object body) {
    return wire().POST(path).addHeader("Authorization", Tokens.admin(principal, path))
        .withRequestBody(body).invoke().status().intValue();
  }

  private <B> B get(String path, Class<B> type) {
    return wire().GET(path).addHeader("Authorization", Tokens.admin("reader", path)).responseBodyAs(type).invoke().body();
  }

  private String actionStatus(String org, String assistant, String key) {
    return get("/orgs/" + org + "/assistants/" + assistant + "/actions/" + key,
        ContextLayerEndpoint.ActionResponse.class).status();
  }

  private void awaitStatus(String org, String assistant, String key, String expected) {
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
        () -> assertThat(actionStatus(org, assistant, key)).isEqualTo(expected));
  }

  /** Creates an assistant owned by alice with one approved release, in the given stage. */
  private String assistant(String org, String stage) {
    var a = "asst-" + id();
    var base = "/orgs/" + org + "/assistants/" + a;
    post("alice", base, new ContextLayerEndpoint.CreateAssistantRequest("watch stalled claims"),
        ContextLayerEndpoint.AssistantResponse.class);
    var release = post("alice", base + "/releases",
        new ContextLayerEndpoint.AddReleaseRequest(SIMPLE_PLAYBOOK), ContextLayerEndpoint.ReleaseResponse.class);
    post("alice", base + "/releases/" + release.releaseId() + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test"),
        ContextLayerEndpoint.AssistantResponse.class);
    post("alice", base + "/stage", LayerTestBase.stageRequest(
        io.akka.contextlayer.domain.AssistantStage.valueOf(stage)), ContextLayerEndpoint.AssistantResponse.class);
    return a;
  }

  private void requestAction(String org, String assistant, String key, String type, String payload) {
    post("alice", "/orgs/" + org + "/assistants/" + assistant + "/actions/" + key,
        new ContextLayerEndpoint.ActionRequest(type, payload), ContextLayerEndpoint.ActionAccepted.class);
  }

  @Test
  public void shadowModeRecordsWhatItWouldDoAndSendsNothing() {
    var org = "org-" + id();
    var a = assistant(org, "SHADOW");
    var key = "k-" + id();

    requestAction(org, a, key, "notify.log", "shadow-" + key);

    awaitStatus(org, a, key, "WOULD_DO");
    assertThat(executor.calls).noneMatch(c -> c.contains("shadow-" + key));
  }

  @Test
  public void unattendedActionRunsOnceEvenWhenRequestedTwice() {
    var org = "org-" + id();
    var a = assistant(org, "LIVE");
    var key = "k-" + id();
    var payload = "once-" + key;

    requestAction(org, a, key, "notify.log", payload);
    awaitStatus(org, a, key, "SUCCEEDED");
    requestAction(org, a, key, "notify.log", payload);

    assertThat(executor.calls.stream().filter(c -> c.contains(payload))).hasSize(1);
  }

  @Test
  public void riskyActionWaitsForApprovalAndThenRuns() {
    var org = "org-" + id();
    var a = assistant(org, "LIVE");
    var key = "k-" + id();
    var payload = "update-" + key;

    requestAction(org, a, key, "record.update", payload);
    awaitStatus(org, a, key, "AWAITING_APPROVAL");
    assertThat(executor.calls).noneMatch(c -> c.contains(payload));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/approvals", ContextLayerEndpoint.ActionList.class).items())
            .extracting(ContextLayerEndpoint.ActionResponse::actionKey).contains(key));

    post("bob", "/orgs/" + org + "/assistants/" + a + "/actions/" + key + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test"),
        ContextLayerEndpoint.ActionAccepted.class);

    awaitStatus(org, a, key, "SUCCEEDED");
    assertThat(get("/orgs/" + org + "/assistants/" + a + "/actions/" + key,
        ContextLayerEndpoint.ActionResponse.class).decidedBy()).isEqualTo("bob");
    assertThat(executor.calls.stream().filter(c -> c.contains(payload))).hasSize(1);
  }

  @Test
  public void aRejectedActionNeverRuns() {
    var org = "org-" + id();
    var a = assistant(org, "LIVE");
    var key = "k-" + id();
    var payload = "reject-" + key;

    requestAction(org, a, key, "record.update", payload);
    awaitStatus(org, a, key, "AWAITING_APPROVAL");
    post("bob", "/orgs/" + org + "/assistants/" + a + "/actions/" + key + "/reject",
        new ContextLayerEndpoint.RejectRequest("not this client"), ContextLayerEndpoint.ActionAccepted.class);

    awaitStatus(org, a, key, "REJECTED");
    assertThat(executor.calls).noneMatch(c -> c.contains(payload));
  }

  @Test
  public void anActionThatIsNeverAllowedIsDeniedAndNeverSent() {
    var org = "org-" + id();
    var a = assistant(org, "LIVE");
    var key = "k-" + id();

    requestAction(org, a, key, "payment.release", "pay-" + key);

    awaitStatus(org, a, key, "DENIED");
    assertThat(executor.calls).noneMatch(c -> c.contains("pay-" + key));
  }

  @Test
  public void aCallThatDiesMidwayIsRecordedAsUnknownAndIsNotRetried() {
    var org = "org-" + id();
    var a = assistant(org, "LIVE");
    var key = "k-" + id();
    var payload = "crash-" + key;

    requestAction(org, a, key, "notify.log", payload);

    awaitStatus(org, a, key, "OUTCOME_UNKNOWN");
    assertThat(executor.calls.stream().filter(c -> c.contains(payload))).hasSize(1);

    post("alice", "/orgs/" + org + "/assistants/" + a + "/actions/" + key + "/resolve",
        new ContextLayerEndpoint.ResolveRequest(false, "target shows no change"),
        ContextLayerEndpoint.ActionResponse.class);
    assertThat(actionStatus(org, a, key)).isEqualTo("FAILED");
    assertThat(executor.calls.stream().filter(c -> c.contains(payload))).hasSize(1);
  }

  @Test
  public void goingLiveNeedsAnApprovedReleaseAndOnlyTheOwnerMayApprove() {
    var org = "org-" + id();
    var a = "asst-" + id();
    var base = "/orgs/" + org + "/assistants/" + a;
    post("alice", base, new ContextLayerEndpoint.CreateAssistantRequest("purpose"),
        ContextLayerEndpoint.AssistantResponse.class);
    var release = post("alice", base + "/releases", new ContextLayerEndpoint.AddReleaseRequest(SIMPLE_PLAYBOOK),
        ContextLayerEndpoint.ReleaseResponse.class);

    assertThat(postStatus("alice", base + "/stage",
        LayerTestBase.stageRequest(io.akka.contextlayer.domain.AssistantStage.LIVE))).isEqualTo(400);
    assertThat(postStatus("mallory", base + "/releases/" + release.releaseId() + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test")))
        .isEqualTo(400);
  }

  @Test
  public void anOrganizationCannotSeeAnotherOrganizationsAssistant() {
    var orgA = "org-" + id();
    var orgB = "org-" + id();
    var a = assistant(orgA, "SHADOW");

    var response = wire().GET("/orgs/" + orgB + "/assistants/" + a).addHeader("Authorization", Tokens.bearer("reader", orgB, "admin")).invoke();
    assertThat(response.status().isSuccess()).isFalse();
  }

  @Test
  public void aRequestWithoutATokenIsRefused() {
    var org = "org-" + id();
    var status = wire().POST("/orgs/" + org + "/assistants/x")
        .withRequestBody(new ContextLayerEndpoint.CreateAssistantRequest("p")).invoke().status().intValue();
    assertThat(status).isIn(400, 401, 403);
  }

  private ContextLayerEndpoint.PackRequest pack(String name, String credentialRef) {
    return new ContextLayerEndpoint.PackRequest(
        name, "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", "https://claims.example.com/openapi.json"),
        credentialRef,
        List.of(new ContextLayerEndpoint.EntityRequest("Claim", "listClaims", "/items", "claimId", List.of("email"),
            List.of("status"), new ContextLayerEndpoint.FreshnessRequest("CACHED", 15),
            new ContextLayerEndpoint.PagingRequest("cursor", 200, "limit", "cursor", "/next", null), new ContextLayerEndpoint.RateRequest(60))),
        List.of(
            new ContextLayerEndpoint.PackActionRequest("attachDocument", "attachDoc",
                io.akka.contextlayer.domain.RiskClass.NEEDS_APPROVAL),
            new ContextLayerEndpoint.PackActionRequest("addNote", "addNote",
                io.akka.contextlayer.domain.RiskClass.UNATTENDED)));
  }

  @Test
  public void aPackIsReviewedByAnotherPersonAndStartsReadOnlyBeforeItsActionsAreAvailable() {
    var org = "org-" + id();
    var packName = "claims";
    var base = "/orgs/" + org + "/connectors/" + packName;
    var a = assistant(org, "LIVE");

    var version = post("alice", base + "/versions", pack(packName, "vault://integrations/claims"),
        ContextLayerEndpoint.PackVersionResponse.class);

    // The importer cannot activate their own version.
    assertThat(postStatus("alice", base + "/versions/" + version.hash() + "/activate",
        LayerTestBase.activateRequest(io.akka.contextlayer.domain.ConnectorPack.Mode.READ_ONLY)))
        .isEqualTo(400);

    // Not active yet: its actions are not available.
    var beforeKey = "k-" + id();
    requestAction(org, a, beforeKey, "claims.addNote", "before-" + beforeKey);
    awaitStatus(org, a, beforeKey, "DENIED");

    // Read-only still exposes no actions.
    post("bob", base + "/versions/" + version.hash() + "/activate",
        LayerTestBase.activateRequest(io.akka.contextlayer.domain.ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);
    var readOnlyKey = "k-" + id();
    requestAction(org, a, readOnlyKey, "claims.addNote", "readonly-" + readOnlyKey);
    awaitStatus(org, a, readOnlyKey, "DENIED");

    // Live exposes the declared actions with their declared risk.
    var live = post("bob", base + "/versions/" + version.hash() + "/activate",
        LayerTestBase.activateRequest(io.akka.contextlayer.domain.ConnectorPack.Mode.LIVE),
        ContextLayerEndpoint.PackResponse.class);
    assertThat(live.mode()).isEqualTo("LIVE");

    var noteKey = "k-" + id();
    requestAction(org, a, noteKey, "claims.addNote", "note-" + noteKey);
    awaitStatus(org, a, noteKey, "SUCCEEDED");

    var attachKey = "k-" + id();
    requestAction(org, a, attachKey, "claims.attachDocument", "attach-" + attachKey);
    awaitStatus(org, a, attachKey, "AWAITING_APPROVAL");

    // An action the pack does not declare does not exist.
    var undeclaredKey = "k-" + id();
    requestAction(org, a, undeclaredKey, "claims.deleteEverything", "x-" + undeclaredKey);
    awaitStatus(org, a, undeclaredKey, "DENIED");
    assertThat(executor.calls).noneMatch(c -> c.contains("x-" + undeclaredKey))
        .noneMatch(c -> c.contains("before-" + beforeKey)).noneMatch(c -> c.contains("readonly-" + readOnlyKey));

    // Switching the pack off removes its actions again.
    post("bob", base + "/deactivate", Map.of(), ContextLayerEndpoint.PackResponse.class);
    var offKey = "k-" + id();
    requestAction(org, a, offKey, "claims.addNote", "off-" + offKey);
    awaitStatus(org, a, offKey, "DENIED");
  }

  @Test
  public void aPackContainingACredentialIsRefused() {
    var org = "org-" + id();
    assertThat(postStatus("alice", "/orgs/" + org + "/connectors/claims/versions",
        pack("claims", "sk-live-1234567890"))).isEqualTo(400);
  }

  @Test
  public void aPacksNameMustMatchItsPathAndPacksAreNotSharedBetweenOrganizations() {
    var orgA = "org-" + id();
    var orgB = "org-" + id();
    assertThat(postStatus("alice", "/orgs/" + orgA + "/connectors/other/versions",
        pack("claims", "vault://x/claims"))).isEqualTo(400);

    post("alice", "/orgs/" + orgA + "/connectors/claims/versions", pack("claims", "vault://x/claims"),
        ContextLayerEndpoint.PackVersionResponse.class);
    assertThat(wire().GET("/orgs/" + orgB + "/connectors/claims").addHeader("Authorization", Tokens.bearer("reader", orgB, "admin")).invoke().status().intValue())
        .isEqualTo(404);
  }

  @Test
  public void highConfidenceMatchesLinkAtOnceAndLowConfidenceOnesWaitForReview() {
    var org = "org-" + id();
    var path = "/orgs/" + org + "/records";
    var email = "pat-" + id() + "@example.com";

    post("alice", path, new ContextLayerEndpoint.IngestRequest("Client", "crm", "c1",
        Map.of("email", email, "name", "Pat Lee", "dob", "1980-01-02")),
        ContextLayerEndpoint.IngestResponse.class);
    var second = post("alice", path, new ContextLayerEndpoint.IngestRequest("Client", "claims", "x9",
        Map.of("email", email.toUpperCase(), "name", "pat  LEE", "dob", "1980-01-02")),
        ContextLayerEndpoint.IngestResponse.class);

    assertThat(second.matches()).extracting(ContextLayerEndpoint.IdentityMatch::rule, ContextLayerEndpoint.IdentityMatch::status)
        .containsExactlyInAnyOrder(
            tuple("email", "CONFIRMED"),
            tuple("name-dob", "PENDING_REVIEW"));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/identity/review", ContextLayerEndpoint.ClusterList.class).items())
            .extracting(ContextLayerEndpoint.ClusterResponse::rule).containsExactly("name-dob"));

    var pending = get("/orgs/" + org + "/identity/review", ContextLayerEndpoint.ClusterList.class).items().get(0);
    assertThat(pending.members()).hasSize(2).allSatisfy(m -> assertThat(m).contains(" / "));
    var confirmed = post("bob", "/orgs/" + org + "/identity/confirm",
        LayerTestBase.clusterRef(pending.rule(), pending.key()),
        ContextLayerEndpoint.ClusterResponse.class);
    // Confirming is a human decision that is applied to the link a moment later; the queue emptying below shows it.
    assertThat(confirmed.status()).isEqualTo("PENDING_REVIEW");

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/identity/review", ContextLayerEndpoint.ClusterList.class).items())
            .isEmpty());
  }
}

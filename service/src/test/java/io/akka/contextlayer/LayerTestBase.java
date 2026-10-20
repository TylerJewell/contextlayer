package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint.ConditionRequest;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.CallRateLimiter;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.HttpSourceCaller;
import io.akka.contextlayer.application.Neo4jEntityStore;
import io.akka.contextlayer.application.SourceCaller;
import io.akka.contextlayer.domain.ConnectorPack;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * Shared set-up for the end-to-end tests: a real Neo4j, two mock source systems (claims and
 * wealth), a recording action executor, and helpers for the HTTP API. Tests are skipped, with a
 * message, when no Neo4j is reachable (see Neo4jEntityStoreTest for the settings).
 *
 * <p>The test kit reuses one instance of the test class, so every field a test can change is reset
 * in {@link #startSources()}.
 */
public abstract class LayerTestBase extends TestKitSupport {

  /** Records every action that leaves the platform. A payload containing "crash" simulates an unknown outcome. */
  public static class RecordingExecutor implements ActionExecutor {
    public final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public Result perform(String orgId, String actionType, String payload) {
      calls.add(orgId + ":" + actionType + ":" + payload);
      if (payload.contains("crash")) throw new IllegalStateException("connection reset");
      return Result.ok("done");
    }
  }

  protected static final RecordingExecutor executor = new RecordingExecutor();
  protected static final RecordingNotifier notifier = new RecordingNotifier();
  protected static Driver driver;
  protected static Neo4jEntityStore store;
  private static String neo4jProblem;

  static {
    var uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
    try {
      driver = GraphDatabase.driver(uri, AuthTokens.basic(
          System.getenv().getOrDefault("NEO4J_USER", "neo4j"),
          System.getenv().getOrDefault("NEO4J_PASSWORD", "localdevpass123")));
      driver.verifyConnectivity();
      store = new Neo4jEntityStore(driver);
      store.ensureSchema();
    } catch (RuntimeException e) {
      neo4jProblem = "Neo4j not reachable at " + uri + ": " + e.getMessage();
    }
  }

  protected static final String CLAIMS = """
      {"items":[
       {"claimId":"1","email":"pat@example.com","status":"OPEN","daysOpen":41},
       {"claimId":"2","email":"sam@example.com","status":"OPEN","daysOpen":36},
       {"claimId":"3","email":"dan@example.com","status":"CLOSED","daysOpen":5}]}""";

  protected static final String ACCOUNTS = """
      {"items":[
       {"accountId":"w1","email":"pat@example.com","balance":412000},
       {"accountId":"w2","email":"sam.o@wealth.example","balance":288500},
       {"accountId":"w3","email":"dan@example.com","balance":1000}]}""";

  protected MockSource claims;
  protected MockSource wealth;
  private final List<String> orgs = new CopyOnWriteArrayList<>();

  /** The dependencies every end-to-end test needs; subclasses add their own settings on top. */
  protected TestKit.Settings baseSettings() {
    return TestDependencies.settings().withDependencyProvider(
        TestDependencies.provider(executor, sourceCaller(), store == null ? TestDependencies.NO_STORE : store, notifier));
  }

  /** Reads the mock sources over HTTP; a test that needs exact control over what a read returns replaces it. */
  protected SourceCaller sourceCaller() {
    return new HttpSourceCaller(ref -> Optional.of("tok"), new EgressPolicy(List.of("localhost")),
        new CallRateLimiter(), HttpSourceCaller.Limits.defaults(), System::currentTimeMillis);
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings();
  }

  @BeforeEach
  protected void startSources() throws IOException {
    assumeTrue(store != null, neo4jProblem);
    notifier.reset();
    claims = new MockSource("/claims", "listClaims", CLAIMS);
    wealth = new MockSource("/accounts", "listAccounts", ACCOUNTS);
  }

  @AfterEach
  protected void stopSources() {
    if (claims != null) claims.close();
    if (wealth != null) wealth.close();
    if (store != null) orgs.forEach(store::deleteOrganization);
  }

  // ---- HTTP helpers

  protected Wire wire() {
    return new Wire(httpClient);
  }

  /** Cleans up after a project a test names itself. */
  protected String adopt(String org) {
    orgs.add(org);
    return org;
  }

  protected String newOrg() {
    var org = "org-" + UUID.randomUUID().toString().substring(0, 8);
    orgs.add(org);
    return org;
  }

  protected <B> B post(String principal, String path, Object body, Class<B> type) {
    var response = wire().POST(path).addHeader("Authorization", Tokens.admin(principal, path))
        .withRequestBody(body).responseBodyAs(type).invoke();
    assertThat(response.status().isSuccess()).as(path + " -> " + response.status()).isTrue();
    return response.body();
  }

  protected int postStatus(String principal, String path, Object body) {
    return wire().POST(path).addHeader("Authorization", Tokens.admin(principal, path))
        .withRequestBody(body).invoke().status().intValue();
  }

  public static ContextLayerEndpoint.StageRequest stageRequest(io.akka.contextlayer.domain.AssistantStage stage) {
    return new ContextLayerEndpoint.StageRequest(stage, "approved in test");
  }

  public static ContextLayerEndpoint.ActivateRequest activateRequest(ConnectorPack.Mode mode) {
    return new ContextLayerEndpoint.ActivateRequest(mode, "approved in test");
  }

  public static ContextLayerEndpoint.ClusterRef clusterRef(String rule, String key) {
    return new ContextLayerEndpoint.ClusterRef(rule, key, "reviewed in test");
  }

  protected <B> B get(String path, Class<B> type) {
    return wire().GET(path).addHeader("Authorization", Tokens.admin("reader", path)).responseBodyAs(type).invoke().body();
  }

  // ---- connectors and questions

  protected ContextLayerEndpoint.PackRequest pack(
      String name, MockSource source, String type, String operation, String idField) {
    return new ContextLayerEndpoint.PackRequest(name, "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", source.specUrl()), "vault://integrations/" + name,
        List.of(new ContextLayerEndpoint.EntityRequest(type, operation, "/items", idField,
            List.of("email", "name", "dob"), List.of(),
            // Read on every question, so each run sees the source as it is now.
            new ContextLayerEndpoint.FreshnessRequest("ON_DEMAND", 0),
            new ContextLayerEndpoint.PagingRequest("none", 100, null, null, null, null),
            new ContextLayerEndpoint.RateRequest(600))),
        List.of());
  }

  protected void connect(String org, String name, ContextLayerEndpoint.PackRequest pack) {
    var base = "/orgs/" + org + "/connectors/" + name;
    var version = post("alice", base + "/versions", pack, ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate",
        activateRequest(ConnectorPack.Mode.READ_ONLY), ContextLayerEndpoint.PackResponse.class);
  }

  /** Claims that are open over 30 days, whose client also holds an account over 250,000. */
  protected ContextLayerEndpoint.QuestionRequest stalledClaimsWithWealth() {
    return new ContextLayerEndpoint.QuestionRequest("Claim",
        List.of(new ConditionRequest("status", "EQ", "OPEN"), new ConditionRequest("daysOpen", "GT", 30)),
        List.of(new ContextLayerEndpoint.LinkRequest("Account", List.of(new ConditionRequest("balance", "GT", 250000)))),
        List.of("Claim.claimId", "Claim.daysOpen", "Account.balance"), 100);
  }

  /** The connector listing is a view that lags the import; wait until both packs are visible. */
  protected void awaitConnectorsVisible(String org) {
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var answer = post("alice", "/orgs/" + org + "/questions", stalledClaimsWithWealth(),
          ContextLayerEndpoint.AnswerResponse.class);
      // A source may be down; what must not remain is "no connector", which means the listing lags.
      assertThat(answer.sources()).flatExtracting(ContextLayerEndpoint.SourceReportView::warnings)
          .noneMatch(w -> w.contains("no active connector provides"));
    });
  }

  protected String connectBoth(String org) {
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId"));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId"));
    awaitConnectorsVisible(org);
    return org;
  }

  protected void removeStoredAccounts(String org) {
    try (var session = driver.session()) {
      session.run("MATCH (n:Entity {orgId: $org, type: 'Account'}) DETACH DELETE n", Map.of("org", org)).consume();
    }
  }
}

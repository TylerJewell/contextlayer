package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint.ConditionRequest;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.CallRateLimiter;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.HttpSourceCaller;
import io.akka.contextlayer.application.Neo4jEntityStore;
import io.akka.contextlayer.domain.ConnectorPack;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

/**
 * The read path end to end: two mock sources, real connector packs, the real entity store
 * (Neo4j), identity linking with review, freshness policies, and a source that goes down. Skipped
 * with a message when no Neo4j is reachable (see Neo4jEntityStoreTest for the settings).
 */
public class QuestionFlowIntegrationTest extends TestKitSupport {
private Wire wire() {    return new Wire(httpClient);  }

  private static Neo4jEntityStore store;
  private static String neo4jProblem;

  static {
    var uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
    try {
      var driver = GraphDatabase.driver(uri, AuthTokens.basic(
          System.getenv().getOrDefault("NEO4J_USER", "neo4j"),
          System.getenv().getOrDefault("NEO4J_PASSWORD", "localdevpass123")));
      driver.verifyConnectivity();
      store = new Neo4jEntityStore(driver);
      store.ensureSchema();
    } catch (RuntimeException e) {
      neo4jProblem = "Neo4j not reachable at " + uri + ": " + e.getMessage();
    }
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    var caller = new HttpSourceCaller(ref -> Optional.of("tok"), new EgressPolicy(List.of("localhost")),
        new CallRateLimiter(), HttpSourceCaller.Limits.defaults(), System::currentTimeMillis);
    return TestDependencies.settings().withDependencyProvider(TestDependencies.provider(
        (org, type, payload) -> ActionExecutor.Result.ok("unused"), caller, store == null ? TestDependencies.NO_STORE : store));
  }

  private HttpServer claims;
  private HttpServer wealth;
  private final AtomicInteger claimsHits = new AtomicInteger();
  private final AtomicInteger wealthHits = new AtomicInteger();
  private volatile boolean wealthDown;
  private final java.util.List<String> orgs = new java.util.concurrent.CopyOnWriteArrayList<>();

  private static final String CLAIMS = """
      {"items":[
       {"claimId":"1","email":"pat@example.com","name":"Pat Lee","dob":"1980-01-02","status":"OPEN","daysOpen":41},
       {"claimId":"2","email":"sam.o@claims.example","name":"Sam Ortiz","dob":"1990-05-05","status":"OPEN","daysOpen":36},
       {"claimId":"3","email":"dan@example.com","name":"Dan Wu","dob":"1975-03-03","status":"CLOSED","daysOpen":5}]}""";

  private static final String ACCOUNTS = """
      {"items":[
       {"accountId":"w1","email":"pat@example.com","balance":412000},
       {"accountId":"w2","email":"sam@wealth.example","name":"Sam Ortiz","dob":"1990-05-05","balance":288500},
       {"accountId":"w3","email":"dan@example.com","balance":1000}]}""";

  @BeforeEach
  void startSources() throws IOException {
    assumeTrue(store != null, neo4jProblem);
    // The test kit reuses one instance of this class, so state from an earlier test must be cleared.
    wealthDown = false;
    claimsHits.set(0);
    wealthHits.set(0);
    claims =source("/claims", "listClaims", CLAIMS, claimsHits, () -> false);
    wealth = source("/accounts", "listAccounts", ACCOUNTS, wealthHits, () -> wealthDown);
  }

  @AfterEach
  void stopSources() {
    if (claims != null) claims.stop(0);
    if (wealth != null) wealth.stop(0);
    if (store != null) orgs.forEach(store::deleteOrganization);
  }

  private HttpServer source(String path, String operationId, String body, AtomicInteger hits, java.util.function.BooleanSupplier down)
      throws IOException {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var origin = "http://localhost:" + server.getAddress().getPort();
    server.createContext("/openapi.json", ex -> respond(ex, 200, """
        {"openapi":"3.0.0","servers":[{"url":"%s"}],"paths":{"%s":{"get":{"operationId":"%s"}}}}"""
        .formatted(origin, path, operationId)));
    server.createContext(path, ex -> {
      hits.incrementAndGet();
      if (down.getAsBoolean()) respond(ex, 500, "{}");
      else if (!"Bearer tok".equals(ex.getRequestHeaders().getFirst("Authorization"))) respond(ex, 401, "{}");
      else respond(ex, 200, body);
    });
    server.start();
    return server;
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  // ---- helpers

  private String newOrg() {
    var org = "org-" + UUID.randomUUID().toString().substring(0, 8);
    orgs.add(org);
    return org;
  }

  private <B> B post(String principal, String path, Object body, Class<B> type) {
    var response = wire().POST(path).addHeader("Authorization", Tokens.admin(principal, path))
        .withRequestBody(body).responseBodyAs(type).invoke();
    assertThat(response.status().isSuccess()).as(path + " -> " + response.status()).isTrue();
    return response.body();
  }

  private ContextLayerEndpoint.PackRequest pack(
      String name, HttpServer server, String type, String operation, String idField, String policy, int ttl) {
    return new ContextLayerEndpoint.PackRequest(name, "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", "http://localhost:" + server.getAddress().getPort() + "/openapi.json"),
        "vault://integrations/" + name,
        List.of(new ContextLayerEndpoint.EntityRequest(type, operation, "/items", idField,
            List.of("email", "name", "dob"), List.of(),
            new ContextLayerEndpoint.FreshnessRequest(policy, ttl),
            new ContextLayerEndpoint.PagingRequest("none", 100, null, null, null, null),
            new ContextLayerEndpoint.RateRequest(600))),
        List.of());
  }

  private void connect(String org, String name, ContextLayerEndpoint.PackRequest pack) {
    var base = "/orgs/" + org + "/connectors/" + name;
    var version = post("alice", base + "/versions", pack, ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate",
        LayerTestBase.activateRequest(ConnectorPack.Mode.READ_ONLY), ContextLayerEndpoint.PackResponse.class);
  }

  private ContextLayerEndpoint.QuestionRequest stalledClaimsWithWealth() {
    return new ContextLayerEndpoint.QuestionRequest("Claim",
        List.of(new ConditionRequest("status", "EQ", "OPEN"), new ConditionRequest("daysOpen", "GT", 30)),
        List.of(new ContextLayerEndpoint.LinkRequest("Account", List.of(new ConditionRequest("balance", "GT", 250000)))),
        List.of("Claim.claimId", "Account.balance"), 100);
  }

  private ContextLayerEndpoint.AnswerResponse ask(String org) {
    return post("alice", "/orgs/" + org + "/questions", stalledClaimsWithWealth(), ContextLayerEndpoint.AnswerResponse.class);
  }

  private static String freshness(ContextLayerEndpoint.AnswerResponse a, String pack) {
    return a.sources().stream().filter(s -> s.pack().equals(pack)).findFirst().orElseThrow().freshness();
  }

  /** The connector listing is a view and lags the import by a moment; ask until both packs are visible. */
  private ContextLayerEndpoint.AnswerResponse askWhenConnected(String org) {
    var answer = new ContextLayerEndpoint.AnswerResponse[1];
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      answer[0] = ask(org);
      assertThat(answer[0].sources()).as(String.valueOf(answer[0].sources()))
          .extracting(ContextLayerEndpoint.SourceReportView::freshness).doesNotContain("UNAVAILABLE");
    });
    return answer[0];
  }

  private List<ContextLayerEndpoint.ClusterResponse> reviewQueue(String org) {
    return wire().GET("/orgs/" + org + "/identity/review").addHeader("Authorization", Tokens.bearer("reader", org, "admin"))
        .responseBodyAs(ContextLayerEndpoint.ClusterList.class).invoke().body().items();
  }

  /**
   * Every identity link waits for a reviewer (custom-cl0004), however strong its match. Pat's and Dan's claims and
   * accounts share an email; a reviewer confirms those two links, as a team would before asking across sources.
   */
  private void confirmEmailLinks(String org) {
    var pending = new java.util.ArrayList<ContextLayerEndpoint.ClusterResponse>();
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      pending.clear();
      reviewQueue(org).stream().filter(c -> c.rule().equals("email")).forEach(pending::add);
      assertThat(pending).hasSize(2);
    });
    for (var cluster : pending) {
      post("rita", "/orgs/" + org + "/identity/confirm", LayerTestBase.clusterRef(cluster.rule(), cluster.key()),
          ContextLayerEndpoint.ClusterResponse.class);
    }
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(ask(org).rows()).extracting(r -> r.get("Claim.claimId")).contains("1"));
  }

  // ---- tests

  @Test
  public void answersAcrossSourcesThroughConfirmedIdentityAndReportsWhatItLeftOut() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));

    askWhenConnected(org);
    confirmEmailLinks(org);
    var first = ask(org);

    // Pat's claim and account share an email, and a reviewer confirmed that link. Sam's share only a name and birth
    // date, and nobody has reviewed that link yet, so the match is left out.
    assertThat(first.rows()).hasSize(1);
    assertThat(first.rows().get(0)).containsEntry("Claim.claimId", "1").containsEntry("Account.balance", 412000);
    assertThat(first.unconfirmedLeftOut()).isEqualTo(1);
    assertThat(first.sources()).extracting(ContextLayerEndpoint.SourceReportView::lastOutcome).containsOnly("OK");
    assertThat(freshness(first, "wealth")).isEqualTo("REFRESHED_NOW");
    assertThat(freshness(first, "claims")).isIn("REFRESHED_NOW", "WITHIN_POLICY");
  }

  @Test
  public void freshnessPoliciesDecideWhichSourcesAreReadAgain() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));
    askWhenConnected(org);
    int claimsBefore = claimsHits.get();
    int wealthBefore = wealthHits.get();

    var second = ask(org);

    assertThat(freshness(second, "claims")).isEqualTo("WITHIN_POLICY");
    assertThat(freshness(second, "wealth")).isEqualTo("REFRESHED_NOW");
    assertThat(claimsHits.get()).isEqualTo(claimsBefore);
    assertThat(wealthHits.get()).isEqualTo(wealthBefore + 1);
  }

  @Test
  public void aConfirmedReviewJoinsTheRecordsAndARejectedOneNeverDoes() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));
    askWhenConnected(org);
    confirmEmailLinks(org);
    assertThat(ask(org).rows()).hasSize(1);

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var queue = wire().GET("/orgs/" + org + "/identity/review").addHeader("Authorization", Tokens.bearer("reader", org, "admin"))
          .responseBodyAs(ContextLayerEndpoint.ClusterList.class).invoke().body();
      assertThat(queue.items()).extracting(ContextLayerEndpoint.ClusterResponse::rule).containsExactly("name-dob");
    });
    var pending = wire().GET("/orgs/" + org + "/identity/review").addHeader("Authorization", Tokens.bearer("reader", org, "admin"))
        .responseBodyAs(ContextLayerEndpoint.ClusterList.class).invoke().body().items().get(0);

    post("dan", "/orgs/" + org + "/identity/confirm",
        LayerTestBase.clusterRef(pending.rule(), pending.key()), ContextLayerEndpoint.ClusterResponse.class);

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var answer = ask(org);
      assertThat(answer.rows()).extracting(r -> r.get("Claim.claimId")).containsExactlyInAnyOrder("1", "2");
      assertThat(answer.unconfirmedLeftOut()).isEqualTo(0);
    });
  }

  @Test
  public void aSourceThatGoesDownGivesAStaleAnswerThatSaysSo() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));
    askWhenConnected(org);
    confirmEmailLinks(org);

    wealthDown = true;
    var answer = ask(org);

    assertThat(freshness(answer, "wealth")).isEqualTo("STALE");
    var report = answer.sources().stream().filter(s -> s.pack().equals("wealth")).findFirst().orElseThrow();
    assertThat(report.lastOutcome()).isEqualTo("FAILED");
    assertThat(report.warnings()).anyMatch(w -> w.contains("HTTP 500")).anyMatch(w -> w.contains("last successful read"));
    assertThat(answer.rows()).hasSize(1);
  }

  @Test
  public void aSourceThatIsDownFromTheStartIsReportedUnavailableNotEmpty() {
    var org = newOrg();
    wealthDown = true;
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));

    // Until the connector listing catches up there is no "wealth" report yet, so keep asking.
    Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var answer = ask(org);
      assertThat(freshness(answer, "wealth")).isEqualTo("UNAVAILABLE");
      assertThat(answer.sources().stream().filter(s -> s.pack().equals("wealth")).findFirst().orElseThrow().warnings())
          .anyMatch(w -> w.contains("HTTP 500"));
      assertThat(answer.rows()).isEmpty();
    });
  }

  @Test
  public void anotherOrganizationSeesNoneOfTheDataAndHasNoConnectors() {
    var org = newOrg();
    var other = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));
    askWhenConnected(org);
    confirmEmailLinks(org);
    assertThat(ask(org).rows()).hasSize(1);

    var answer = ask(other);

    assertThat(answer.rows()).isEmpty();
    assertThat(answer.sources()).extracting(ContextLayerEndpoint.SourceReportView::freshness).containsOnly("UNAVAILABLE");
  }

  @Test
  public void aMalformedQuestionIsRefusedBeforeAnythingIsRead() {
    var org = newOrg();
    var bad = new ContextLayerEndpoint.QuestionRequest("Claim) DETACH DELETE (n", List.of(), List.of(),
        List.of("Claim.claimId"), 10);
    var status = wire().POST("/orgs/" + org + "/questions").addHeader("Authorization", Tokens.bearer("alice", org, "admin"))
        .withRequestBody(bad).invoke().status().intValue();
    assertThat(status).isEqualTo(400);
  }

  @Test
  public void anUnknownOperatorIsRefused() {
    var org = newOrg();
    var bad = new ContextLayerEndpoint.QuestionRequest("Claim",
        List.of(new ConditionRequest("status", "LIKE", "x")), List.of(), List.of("Claim.claimId"), 10);
    assertThat(wire().POST("/orgs/" + org + "/questions").addHeader("Authorization", Tokens.bearer("alice", org, "admin"))
        .withRequestBody(bad).invoke().status().intValue()).isEqualTo(400);
  }

  @Test
  public void anIdentityReviewThatConfirmedLinksAlreadyCoverIsNotInTheOversightQueue() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId", "CACHED", 15));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId", "ON_DEMAND", 0));
    askWhenConnected(org);
    confirmEmailLinks(org);

    // Once the email links are confirmed, only Sam's name and birth date match still needs a person.
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var queue = wire().GET("/orgs/" + org + "/identity/review").addHeader("Authorization", Tokens.bearer("reader", org, "admin"))
          .responseBodyAs(ContextLayerEndpoint.ClusterList.class).invoke().body();
      assertThat(queue.items()).hasSize(1);
      var events = wire().GET("/oversight/events?state=pending&subject=identity")
          .addHeader("Authorization", Tokens.bearer("reader", org, "admin")).invoke().body().utf8String();
      assertThat(events.split("\"id\":").length - 1).isEqualTo(1);
    });
  }
}

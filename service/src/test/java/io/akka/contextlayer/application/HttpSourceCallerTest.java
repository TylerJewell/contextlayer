package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.contextlayer.domain.PackDefinition;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpSourceCallerTest {

  private HttpServer server;
  private String origin;
  /** Every data request the source received: path?query plus the Authorization header. */
  private final List<String> dataRequests = new CopyOnWriteArrayList<>();
  private volatile String openApi;

  @BeforeEach
  void startSource() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    origin = "http://localhost:" + server.getAddress().getPort();
    openApi = spec("get");
    server.createContext("/openapi.json", ex -> respond(ex, 200, openApi));
    server.start();
  }

  @AfterEach
  void stopSource() {
    server.stop(0);
  }

  private String spec(String method) {
    return """
        {"openapi":"3.0.0","servers":[{"url":"%s"}],
         "paths":{"/claims":{"%s":{"operationId":"listClaims"}}}}""".formatted(origin, method);
  }

  private void respond(HttpExchange ex, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  private void data(String path, java.util.function.Function<String, Optional<String>> handler, int failAfter) {
    var served = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(path, ex -> {
      var line = ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery()
          + " auth=" + ex.getRequestHeaders().getFirst("Authorization");
      dataRequests.add(line);
      if (served.incrementAndGet() > failAfter) {
        respond(ex, 500, "{}");
        return;
      }
      var body = handler.apply(String.valueOf(ex.getRequestURI().getRawQuery()));
      if (body.isEmpty()) respond(ex, 302, "{}");
      else respond(ex, 200, body.get());
    });
  }

  private PackDefinition pack(PackDefinition.Paging paging, int callsPerMinute) {
    return new PackDefinition("claims", "1.0.0",
        new PackDefinition.Source("openapi", origin + "/openapi.json"),
        "vault://integrations/claims",
        List.of(new PackDefinition.EntityMapping("Claim", "listClaims", "/items", "claimId", List.of(),
            List.of("status"), new PackDefinition.Freshness("CACHED", 15), paging,
            new PackDefinition.Rate(callsPerMinute))),
        List.of());
  }

  private PackDefinition.Paging cursorPaging() {
    return new PackDefinition.Paging("cursor", 2, "limit", "cursor", "/next", null);
  }

  private HttpSourceCaller caller(Map<String, String> secrets, List<String> hosts, HttpSourceCaller.Limits limits) {
    SecretProvider provider = ref -> Optional.ofNullable(secrets.get(ref));
    return new HttpSourceCaller(provider, new EgressPolicy(hosts), new CallRateLimiter(), limits,
        System::currentTimeMillis);
  }

  private HttpSourceCaller caller() {
    return caller(Map.of("vault://integrations/claims", "s3cret"), List.of("localhost"),
        HttpSourceCaller.Limits.defaults());
  }

  private SourceCaller.Result fetch(HttpSourceCaller c, PackDefinition p, Map<String, String> filters) {
    return c.fetchEntities("org", p, p.entities().get(0), filters);
  }

  /** Two pages of two claims each, linked by a cursor. */
  private void twoCursorPages() {
    data("/claims", query -> Optional.of(query.contains("cursor=c2")
        ? "{\"items\":[{\"claimId\":\"3\"},{\"claimId\":\"4\"}]}"
        : "{\"items\":[{\"claimId\":\"1\"},{\"claimId\":\"2\"}],\"next\":\"c2\"}"), Integer.MAX_VALUE);
  }

  @Test
  void followsACursorThroughAllPagesAndSendsTheCredentialAndDeclaredFilters() {
    twoCursorPages();
    var p = pack(cursorPaging(), 60);

    var result = fetch(caller(), p, Map.of("status", "OPEN"));

    assertThat(result.status()).isEqualTo(SourceCaller.Status.OK);
    assertThat(result.records()).extracting(r -> r.get("claimId")).containsExactly("1", "2", "3", "4");
    assertThat(dataRequests).hasSize(2).allMatch(r -> r.endsWith("auth=Bearer s3cret"))
        .allMatch(r -> r.contains("status=OPEN") && r.contains("limit=2"));
    assertThat(dataRequests.get(1)).contains("cursor=c2");
  }

  @Test
  void followsPageNumbersUntilAShortPage() {
    data("/claims", query -> Optional.of(query.contains("page=2")
        ? "{\"items\":[{\"claimId\":\"3\"}]}"
        : "{\"items\":[{\"claimId\":\"1\"},{\"claimId\":\"2\"}]}"), Integer.MAX_VALUE);
    var p = pack(new PackDefinition.Paging("page", 2, "limit", null, null, "page"), 60);

    var result = fetch(caller(), p, Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.OK);
    assertThat(result.records()).hasSize(3);
  }

  @Test
  void aFilterThePackDoesNotDeclareIsRefusedWithoutCallingTheSource() {
    twoCursorPages();
    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of("ssn", "123"));

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("ssn"));
    assertThat(dataRequests).isEmpty();
  }

  @Test
  void aHostOffTheAllowListIsNeverCalled() {
    twoCursorPages();
    var result = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("other.example.com"),
        HttpSourceCaller.Limits.defaults()), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("allowed list"));
    assertThat(dataRequests).isEmpty();
  }

  @Test
  void aMissingCredentialStopsBeforeAnyCall() {
    twoCursorPages();
    var result = fetch(caller(Map.of(), List.of("localhost"), HttpSourceCaller.Limits.defaults()),
        pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("credential"));
    assertThat(dataRequests).isEmpty();
  }

  @Test
  void theCredentialNeverAppearsInAResult() {
    twoCursorPages();
    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());
    assertThat(result.toString()).doesNotContain("s3cret");

    var failed = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("nowhere"),
        HttpSourceCaller.Limits.defaults()), pack(cursorPaging(), 60), Map.of());
    assertThat(failed.toString()).doesNotContain("s3cret");
  }

  @Test
  void aFailureAfterSomeRecordsIsPartialAndKeepsWhatWasRead() {
    data("/claims", query -> Optional.of("{\"items\":[{\"claimId\":\"1\"},{\"claimId\":\"2\"}],\"next\":\"c2\"}"), 1);

    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.PARTIAL);
    assertThat(result.records()).hasSize(2);
    assertThat(result.warnings()).anyMatch(w -> w.contains("HTTP 500"));
  }

  @Test
  void aFailureOnTheFirstPageIsAFailureNotAnEmptyResult() {
    data("/claims", query -> Optional.of("{}"), 0);

    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.records()).isEmpty();
  }

  @Test
  void aRedirectIsNotFollowed() {
    data("/claims", query -> Optional.empty(), Integer.MAX_VALUE);

    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("HTTP 302"));
  }

  @Test
  void theRecordCapStopsTheReadAndSaysSo() {
    twoCursorPages();
    var limits = new HttpSourceCaller.Limits(50, 3, 5_000_000, Duration.ofSeconds(10), 2_000);

    var result = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("localhost"), limits),
        pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.PARTIAL);
    assertThat(result.records()).hasSize(3);
    assertThat(result.warnings()).anyMatch(w -> w.contains("3 records"));
  }

  @Test
  void thePageCapStopsAnEndlessCursor() {
    data("/claims", query -> Optional.of("{\"items\":[{\"claimId\":\"x\"}],\"next\":\"again\"}"), Integer.MAX_VALUE);
    var limits = new HttpSourceCaller.Limits(3, 5_000, 5_000_000, Duration.ofSeconds(10), 2_000);

    var result = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("localhost"), limits),
        pack(cursorPaging(), 600), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.PARTIAL);
    assertThat(dataRequests).hasSize(3);
  }

  @Test
  void theDeclaredCallRateIsAHardCeilingEvenWhenMorePagesExist() {
    twoCursorPages();
    var limits = new HttpSourceCaller.Limits(50, 5_000, 5_000_000, Duration.ofSeconds(10), 100);

    var result = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("localhost"), limits),
        pack(cursorPaging(), 1), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.PARTIAL);
    assertThat(dataRequests).hasSize(1);
    assertThat(result.warnings()).anyMatch(w -> w.contains("call rate"));
  }

  @Test
  void anOversizedResponseIsRefused() {
    data("/claims", query -> Optional.of("{\"items\":[" + "{\"claimId\":\"1\"},".repeat(100) + "{\"claimId\":\"2\"}]}"),
        Integer.MAX_VALUE);
    var limits = new HttpSourceCaller.Limits(50, 5_000, 200, Duration.ofSeconds(10), 2_000);

    var result = fetch(caller(Map.of("vault://integrations/claims", "s3cret"), List.of("localhost"), limits),
        pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("larger than"));
  }

  @Test
  void aResponseWithoutTheDeclaredListIsAFailure() {
    data("/claims", query -> Optional.of("{\"rows\":[]}"), Integer.MAX_VALUE);

    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("/items"));
  }

  @Test
  void anOperationThatIsNotAReadIsRefused() {
    openApi = spec("post");
    twoCursorPages();

    var result = fetch(caller(), pack(cursorPaging(), 60), Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("GET"));
    assertThat(dataRequests).isEmpty();
  }

  @Test
  void anUnknownOperationIsReported() {
    twoCursorPages();
    var p = pack(cursorPaging(), 60);
    var wrong = new PackDefinition.EntityMapping("Claim", "noSuchOperation", "/items", "claimId", List.of(),
        List.of("status"), new PackDefinition.Freshness("CACHED", 15), cursorPaging(), new PackDefinition.Rate(60));

    var result = caller().fetchEntities("org", p, wrong, Map.of());

    assertThat(result.status()).isEqualTo(SourceCaller.Status.FAILED);
    assertThat(result.warnings()).anyMatch(w -> w.contains("noSuchOperation"));
  }

  @Test
  void egressRulesMatchExactHostsAndSubdomains() {
    var policy = new EgressPolicy(new ArrayList<>(List.of("api.example.com", "*.corp.example.com")));
    assertThat(policy.allows("api.example.com")).isTrue();
    assertThat(policy.allows("API.example.com")).isTrue();
    assertThat(policy.allows("a.corp.example.com")).isTrue();
    assertThat(policy.allows("corp.example.com")).isFalse();
    assertThat(policy.allows("evilcorp.example.com")).isFalse();
    assertThat(policy.allows("example.com")).isFalse();
    assertThat(new EgressPolicy(List.of()).allows("anything")).isFalse();
  }
}

package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.akka.contextlayer.application.Notifier.Message;
import io.akka.contextlayer.application.Notifier.Route;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpNotifierTest {

  private record Got(String body, String authorization, String idempotencyKey) {}

  private HttpServer server;
  private final List<Got> got = new CopyOnWriteArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();
  private volatile int[] statuses = {200};

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/hook", ex -> {
      got.add(new Got(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
          ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Idempotency-Key")));
      int n = calls.getAndIncrement();
      int status = statuses[Math.min(n, statuses.length - 1)];
      ex.sendResponseHeaders(status, -1);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String url() {
    return "http://localhost:" + server.getAddress().getPort() + "/hook";
  }

  private HttpNotifier notifier(List<Duration> waits) {
    return new HttpNotifier(ref -> "vault://team".equals(ref) ? Optional.of("secret") : Optional.empty(),
        new EgressPolicy(List.of("localhost")), Duration.ofSeconds(2), waits);
  }

  private static Message message() {
    return new Message("case.opened", "acme", "Claims Team B", "New case for pat@example.com",
        "Call 555-123-4567 about it.", Map.of("caseId", "acme:c1"), null, "2026-10-19T10:00:00Z");
  }

  @Test
  public void aJsonRouteGetsTheMessageTheCredentialAndAnIdempotencyKeyWithPersonalDataMasked() {
    var result = notifier(List.of()).send(new Route(url(), "json", "vault://team"), message(), "acme:nt:case-1");

    assertThat(result.delivered()).isTrue();
    var sent = got.get(0);
    assertThat(sent.authorization()).isEqualTo("Bearer secret");
    assertThat(sent.idempotencyKey()).isEqualTo("acme:nt:case-1");
    assertThat(sent.body()).contains("\"type\":\"case.opened\"").contains("\"caseId\":\"acme:c1\"")
        .doesNotContain("pat@example.com").doesNotContain("555-123-4567").doesNotContain("dueAt");
  }

  @Test
  public void aSlackRouteGetsATextMessageAndNoCredentialHeader() {
    notifier(List.of()).send(new Route(url(), "slack", ""), message(), "k");

    assertThat(got.get(0).authorization()).isNull();
    assertThat(got.get(0).body()).startsWith("{\"text\":\"New case for ").contains("Call ");
  }

  @Test
  public void aFailureThatMayPassIsTriedAgainAndOneTheReceiverRefusedIsNot() {
    statuses = new int[] {503, 429, 200};
    var retried = notifier(List.of(Duration.ZERO, Duration.ZERO)).send(new Route(url(), "json", ""), message(), "k");
    assertThat(retried.delivered()).isTrue();
    assertThat(retried.detail()).contains("after 3 tries");
    assertThat(calls.get()).isEqualTo(3);

    calls.set(0);
    statuses = new int[] {404};
    var refused = notifier(List.of(Duration.ZERO, Duration.ZERO)).send(new Route(url(), "json", ""), message(), "k");
    assertThat(refused.delivered()).isFalse();
    assertThat(refused.detail()).isEqualTo("the receiver answered HTTP 404");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  public void aReceiverThatKeepsFailingIsGivenUpOnAfterTheTriesAreUsed() {
    statuses = new int[] {500};

    var result = notifier(List.of(Duration.ZERO, Duration.ZERO)).send(new Route(url(), "json", ""), message(), "k");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("HTTP 500").contains("gave up after 3 tries");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  public void anUnreachableReceiverIsReportedAndRetried() {
    server.stop(0);

    var result = notifier(List.of(Duration.ZERO)).send(new Route(url(), "json", ""), message(), "k");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("could not reach the receiver").contains("gave up after 2 tries");
  }

  @Test
  public void aRedirectIsNotFollowed() {
    statuses = new int[] {302};

    var result = notifier(List.of()).send(new Route(url(), "json", ""), message(), "k");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).isEqualTo("the receiver answered HTTP 302");
  }

  @Test
  public void anAddressOffTheAllowListOrWithoutACredentialIsRefusedBeforeAnythingIsSent() {
    var off = notifier(List.of()).send(new Route("http://evil.example/hook", "json", ""), message(), "k");
    var noCredential = notifier(List.of()).send(new Route(url(), "json", "vault://missing"), message(), "k");
    var notWeb = notifier(List.of()).send(new Route("ftp://localhost/x", "json", ""), message(), "k");

    assertThat(off.detail()).endsWith("allowed list of hosts");
    assertThat(noCredential.detail()).isEqualTo("the credential for this route is not available");
    assertThat(notWeb.detail()).isEqualTo("the route's address is not a web address");
    assertThat(got).isEmpty();
  }
}

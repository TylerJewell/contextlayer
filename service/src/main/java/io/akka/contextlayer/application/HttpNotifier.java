package io.akka.contextlayer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.contextlayer.governance.PersonalDataMasker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Delivers a notification by posting it to the route's URL. The host must be on the egress list, a
 * redirect is never followed, and what is sent has personal data masked: the message points to the
 * console and does not carry records. A delivery that fails in a way that may pass (a timeout, a
 * 429 or a 5xx) is tried again after a short wait, up to the number of waits given; one the receiver
 * refused (any other 4xx) is not.
 */
public class HttpNotifier implements Notifier {

  private final SecretProvider secrets;
  private final EgressPolicy egress;
  private final Duration timeout;
  private final List<Duration> retryWaits;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpNotifier(SecretProvider secrets, EgressPolicy egress, Duration timeout, List<Duration> retryWaits) {
    this.secrets = secrets;
    this.egress = egress;
    this.timeout = timeout;
    this.retryWaits = List.copyOf(retryWaits);
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
  }

  @Override
  public Result send(Route route, Message message, String idempotencyKey) {
    URI uri;
    try {
      uri = URI.create(route.url());
    } catch (IllegalArgumentException e) {
      return Result.failed("the route's address is not valid");
    }
    if (uri.getHost() == null || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
      return Result.failed("the route's address is not a web address");
    }
    if (!egress.allows(uri.getHost())) return Result.failed("the route is not on the " + SourceCaller.EGRESS_REFUSAL);

    String credential = null;
    if (route.credentialRef() != null && !route.credentialRef().isBlank()) {
      credential = secrets.resolve(route.credentialRef()).orElse(null);
      if (credential == null) return Result.failed("the credential for this route is not available");
    }

    var builder = HttpRequest.newBuilder(uri).timeout(timeout)
        .header("Content-Type", "application/json").header("Idempotency-Key", idempotencyKey);
    if (credential != null) builder.header("Authorization", "Bearer " + credential);
    var request = builder.POST(HttpRequest.BodyPublishers.ofString(body(route, message))).build();

    String last = "";
    for (int attempt = 0; attempt <= retryWaits.size(); attempt++) {
      if (attempt > 0) {
        try {
          Thread.sleep(retryWaits.get(attempt - 1).toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Result.failed("interrupted");
        }
      }
      try {
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status / 100 == 2) return Result.ok("delivered (HTTP " + status + ")" + (attempt > 0 ? " after " + (attempt + 1) + " tries" : ""));
        last = "the receiver answered HTTP " + status;
        if (status != 429 && status / 100 != 5) return Result.failed(last);
      } catch (IOException e) {
        last = "could not reach the receiver (" + e.getClass().getSimpleName() + ")";
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Result.failed("interrupted");
      }
    }
    return Result.failed(last + "; gave up after " + (retryWaits.size() + 1) + " tries");
  }

  private String body(Route route, Message m) {
    var title = PersonalDataMasker.mask(m.title()).text();
    var summary = PersonalDataMasker.mask(m.summary()).text();
    ObjectNode body = mapper.createObjectNode();
    if ("slack".equals(route.format())) {
      body.put("text", title + "\n" + summary);
    } else {
      body.put("type", m.type()).put("organization", m.organization()).put("route", m.route())
          .put("title", title).put("summary", summary).put("at", m.at());
      if (m.dueAt() != null) body.put("dueAt", m.dueAt());
      var reference = body.putObject("reference");
      m.reference().forEach(reference::put);
    }
    return body.toString();
  }
}

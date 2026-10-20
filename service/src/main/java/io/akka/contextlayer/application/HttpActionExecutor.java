package io.akka.contextlayer.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sends an action to the source system of the pack that offers it, as the OpenAPI operation the
 * pack names. Built-in actions are handed to the fallback.
 *
 * <p>What the answer means is the whole point of this class, because an action can have a real-world
 * effect and is never sent twice:
 * <ul>
 *   <li>The request never left (the credential, the operation, the payload or the host was refused,
 *       or the connection could not be made): the action definitely did not happen. {@code failed}.
 *   <li>The system answered 2xx: it happened. {@code ok}.
 *   <li>The system answered 3xx or 4xx: it refused. A redirect is never followed. {@code failed}.
 *   <li>The system answered 5xx, or the connection broke or timed out after it was made: the
 *       system may or may not have applied it. This throws, so the workflow records the outcome as
 *       unknown and a person decides. It is never retried here.
 * </ul>
 * Every attempt carries the same {@code Idempotency-Key}, so a system that honours it is safe even
 * if a person later asks for the action again.
 */
public class HttpActionExecutor implements ActionExecutor {

  private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z0-9_]+)}");
  private static final List<String> WRITES = List.of("post", "put", "patch", "delete");

  private final HttpSourceCaller operations;
  private final Duration timeout;
  private final ActionExecutor fallback;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpActionExecutor(HttpSourceCaller operations, Duration timeout, ActionExecutor fallback) {
    this.operations = operations;
    this.timeout = timeout;
    this.fallback = fallback;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
  }

  @Override
  public Result perform(String orgId, String actionType, String payload) {
    return fallback.perform(orgId, actionType, payload);
  }

  @Override
  public Result performPack(String orgId, PackCall call, String payload, String idempotencyKey) {
    var resolved = operations.resolve(call.pack(), call.action().operation());
    if (resolved.error() != null) return Result.failed(resolved.error());
    if (!WRITES.contains(resolved.method())) {
      return Result.failed("operation " + call.action().operation() + " is a " + resolved.method().toUpperCase()
          + "; an action must change something (POST, PUT, PATCH or DELETE)");
    }

    JsonNode parsed;
    try {
      parsed = mapper.readTree(payload == null || payload.isBlank() ? "{}" : payload);
    } catch (IOException e) {
      return Result.failed("the payload is not valid JSON");
    }
    if (!parsed.isObject()) return Result.failed("the payload must be a JSON object");
    var body = (ObjectNode) parsed.deepCopy();

    // Values for {placeholders} in the path come from the payload and are not sent again in the body.
    var path = new StringBuilder();
    var matcher = PATH_PARAMETER.matcher(resolved.path());
    int last = 0;
    while (matcher.find()) {
      var name = matcher.group(1);
      var value = body.get(name);
      if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) {
        return Result.failed("the payload has no value for " + name + ", which the operation's path needs");
      }
      path.append(resolved.path(), last, matcher.start())
          .append(URLEncoder.encode(value.asText(), StandardCharsets.UTF_8).replace("+", "%20"));
      last = matcher.end();
      body.remove(name);
    }
    path.append(resolved.path().substring(last));

    URI target;
    try {
      target = URI.create(resolved.base().toString().replaceAll("/+$", "") + path);
    } catch (IllegalArgumentException e) {
      return Result.failed("the request address is not valid");
    }
    if (target.getHost() == null || !target.getHost().equalsIgnoreCase(resolved.base().getHost())) {
      return Result.failed("the request address does not stay on the source's host");
    }

    var builder = HttpRequest.newBuilder(target)
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + resolved.credential())
        .header("Idempotency-Key", idempotencyKey);
    var bytes = body.isEmpty() && "delete".equals(resolved.method())
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body.toString());
    var request = builder.header("Content-Type", "application/json")
        .method(resolved.method().toUpperCase(), bytes).build();

    HttpResponse<Void> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (ConnectException | HttpConnectTimeoutException e) {
      return Result.failed("could not connect to the source; nothing was sent");
    } catch (IOException e) {
      throw new IllegalStateException("the connection to the source broke before an answer: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the source");
    }

    int status = response.statusCode();
    if (status / 100 == 2) return Result.ok("the source answered HTTP " + status);
    if (status / 100 == 5) throw new IllegalStateException("the source answered HTTP " + status + ": it may or may not have applied the action");
    return Result.failed("the source refused the action: HTTP " + status);
  }
}

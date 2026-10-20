package io.akka.contextlayer.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.akka.contextlayer.application.SourceCaller.EGRESS_REFUSAL;

import io.akka.contextlayer.domain.PackDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Reads entity records from a source that describes itself with an OpenAPI document (JSON).
 *
 * <p>Every call is bounded: hosts must be on the egress allow-list, redirects are never followed,
 * only GET operations are used, only filters the pack declares are sent, and page count, record
 * count, response size, time and call rate are all capped. The credential is looked up by
 * reference for each call and appears only in the Authorization header.
 */
public class HttpSourceCaller implements SourceCaller {

  public record Limits(int maxPages, int maxRecords, int maxResponseBytes, Duration timeout, long maxRateWaitMillis) {
    public static Limits defaults() {
      return new Limits(50, 5_000, 5_000_000, Duration.ofSeconds(10), 2_000);
    }
  }

  private static final long SPEC_TTL_MILLIS = 5 * 60_000;
  private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

  private record Operation(String method, String path) {}

  private record CachedSpec(JsonNode spec, long loadedAt) {}

  private final SecretProvider secrets;
  private final EgressPolicy egress;
  private final CallRateLimiter rateLimiter;
  private final Limits limits;
  private final LongSupplier nowMillis;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, CachedSpec> specCache = new ConcurrentHashMap<>();

  public HttpSourceCaller(
      SecretProvider secrets, EgressPolicy egress, CallRateLimiter rateLimiter, Limits limits, LongSupplier nowMillis) {
    this.secrets = secrets;
    this.egress = egress;
    this.rateLimiter = rateLimiter;
    this.limits = limits;
    this.nowMillis = nowMillis;
    this.http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(limits.timeout())
        .build();
  }

  @Override
  public Result fetchEntities(
      String orgId, PackDefinition pack, PackDefinition.EntityMapping entity, Map<String, String> filters) {
    var undeclared = filters.keySet().stream()
        .filter(f -> entity.filters() == null || !entity.filters().contains(f))
        .sorted()
        .toList();
    if (!undeclared.isEmpty()) {
      return Result.failed("filters not declared by the pack for " + entity.type() + ": " + undeclared);
    }
    if (!"openapi".equals(pack.source().kind())) {
      return Result.failed("source kind " + pack.source().kind() + " is not supported yet");
    }

    var credential = secrets.resolve(pack.credentialRef());
    if (credential.isEmpty()) return Result.failed("the credential for this pack is not available");

    var specUri = parse(pack.source().ref());
    if (specUri.isEmpty() || !egress.allows(specUri.get().getHost())) {
      return Result.refusedHost("the source");
    }
    JsonNode spec;
    try {
      spec = loadSpec(pack.source().ref(), specUri.get(), credential.get());
    } catch (IOException | RuntimeException e) {
      return Result.failed("could not read the source's API description: " + e.getMessage());
    }

    var operation = findOperation(spec, entity.operation());
    if (operation.isEmpty()) return Result.failed("operation " + entity.operation() + " not found in the source");
    if (!"get".equals(operation.get().method())) {
      return Result.failed("only read (GET) operations can fetch entities");
    }
    if (operation.get().path().contains("{")) {
      return Result.failed("operations with path parameters are not supported yet");
    }
    var base = baseUrl(spec, specUri.get());
    if (base.isEmpty() || !egress.allows(base.get().getHost())) {
      return Result.refusedHost("the source's server");
    }
    return readPages(orgId, pack, entity, filters, credential.get(), base.get(), operation.get());
  }

  private Result readPages(
      String orgId, PackDefinition pack, PackDefinition.EntityMapping entity, Map<String, String> filters,
      String credential, URI base, Operation operation) {
    var paging = entity.paging();
    var records = new ArrayList<Map<String, Object>>();
    var warnings = new ArrayList<String>();
    var rateKey = orgId + "|" + pack.name() + "|" + entity.type();
    String cursor = null;
    int page = 1;
    int pages = 0;

    while (true) {
      if (pages >= limits.maxPages()) {
        warnings.add("stopped after " + limits.maxPages() + " pages; more records exist");
        break;
      }
      if (!rateLimiter.acquire(rateKey, entity.rate().callsPerMinute(), limits.maxRateWaitMillis())) {
        warnings.add("stopped: the declared call rate (" + entity.rate().callsPerMinute() + "/min) was reached");
        break;
      }
      var query = new LinkedHashMap<String, String>(filters);
      if (!blank(paging.pageSizeParam())) query.put(paging.pageSizeParam(), String.valueOf(paging.maxPageSize()));
      if ("cursor".equals(paging.style()) && cursor != null) query.put(paging.cursorParam(), cursor);
      if ("page".equals(paging.style())) query.put(paging.pageParam(), String.valueOf(page));

      JsonNode body;
      try {
        body = get(url(base, operation.path(), query), credential);
      } catch (IOException | RuntimeException e) {
        warnings.add("call failed: " + e.getMessage());
        break;
      }
      pages++;

      var array = body.at(entity.recordsPath());
      if (!array.isArray()) {
        warnings.add("the response has no list at " + entity.recordsPath());
        break;
      }
      int skipped = 0;
      for (var element : array) {
        if (!element.isObject()) {
          skipped++;
        } else if (records.size() < limits.maxRecords()) {
          records.add(mapper.convertValue(element, new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
      }
      if (skipped > 0) warnings.add(skipped + " non-object entries were skipped");
      if (records.size() >= limits.maxRecords() && !array.isEmpty()) {
        warnings.add("stopped at " + limits.maxRecords() + " records");
        break;
      }

      if ("cursor".equals(paging.style())) {
        var next = body.at(paging.nextCursorPath());
        if (next.isMissingNode() || next.isNull() || next.asText().isBlank()) return finish(records, warnings, true);
        cursor = next.asText();
      } else if ("page".equals(paging.style())) {
        if (array.size() < paging.maxPageSize()) return finish(records, warnings, true);
        page++;
      } else {
        return finish(records, warnings, true);
      }
    }
    return finish(records, warnings, false);
  }

  private Result finish(List<Map<String, Object>> records, List<String> warnings, boolean reachedEnd) {
    if (reachedEnd && warnings.stream().noneMatch(w -> w.startsWith("call failed") || w.startsWith("stopped"))) {
      return new Result(warnings.isEmpty() ? Status.OK : Status.PARTIAL, List.copyOf(records), List.copyOf(warnings));
    }
    if (records.isEmpty()) return new Result(Status.FAILED, List.of(), List.copyOf(warnings));
    return new Result(Status.PARTIAL, List.copyOf(records), List.copyOf(warnings));
  }

  private JsonNode loadSpec(String ref, URI uri, String credential) throws IOException {
    var cached = specCache.get(ref);
    if (cached != null && nowMillis.getAsLong() - cached.loadedAt() < SPEC_TTL_MILLIS) return cached.spec();
    var spec = get(uri, credential);
    specCache.put(ref, new CachedSpec(spec, nowMillis.getAsLong()));
    return spec;
  }

  private JsonNode get(URI uri, String credential) throws IOException {
    var request = HttpRequest.newBuilder(uri)
        .timeout(limits.timeout())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + credential)
        .GET()
        .build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        if (response.statusCode() / 100 != 2) {
          throw new IOException("the source answered HTTP " + response.statusCode());
        }
        var bytes = in.readNBytes(limits.maxResponseBytes() + 1);
        if (bytes.length > limits.maxResponseBytes()) {
          throw new IOException("the response is larger than " + limits.maxResponseBytes() + " bytes");
        }
        return mapper.readTree(bytes);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted");
    }
  }

  /**
   * Finds an operation of the pack's source by its operation id, applying the same rules as a read:
   * the credential must resolve and the hosts must be on the egress list. Used to send an action.
   *
   * @param error why it could not be resolved; the request was not and will not be sent
   */
  record Resolved(String method, URI base, String path, String credential, String error) {
    static Resolved failed(String error) {
      return new Resolved(null, null, null, null, error);
    }
  }

  Resolved resolve(PackDefinition pack, String operationId) {
    if (!"openapi".equals(pack.source().kind())) {
      return Resolved.failed("source kind " + pack.source().kind() + " is not supported yet");
    }
    var credential = secrets.resolve(pack.credentialRef());
    if (credential.isEmpty()) return Resolved.failed("the credential for this pack is not available");
    var specUri = parse(pack.source().ref());
    if (specUri.isEmpty() || !egress.allows(specUri.get().getHost())) {
      return Resolved.failed("the source is not on the " + EGRESS_REFUSAL);
    }
    JsonNode spec;
    try {
      spec = loadSpec(pack.source().ref(), specUri.get(), credential.get());
    } catch (IOException | RuntimeException e) {
      return Resolved.failed("could not read the source's API description: " + e.getMessage());
    }
    var operation = findOperation(spec, operationId);
    if (operation.isEmpty()) return Resolved.failed("operation " + operationId + " not found in the source");
    var base = baseUrl(spec, specUri.get());
    if (base.isEmpty() || !egress.allows(base.get().getHost())) {
      return Resolved.failed("the source's server is not on the " + EGRESS_REFUSAL);
    }
    return new Resolved(operation.get().method(), base.get(), operation.get().path(), credential.get(), null);
  }

  private Optional<Operation> findOperation(JsonNode spec, String operationId) {
    for (var path : spec.path("paths").properties()) {
      for (var method : METHODS) {
        if (operationId.equals(path.getValue().path(method).path("operationId").asText())) {
          return Optional.of(new Operation(method, path.getKey()));
        }
      }
    }
    return Optional.empty();
  }

  private Optional<URI> baseUrl(JsonNode spec, URI specUri) {
    var declared = spec.at("/servers/0/url").asText("");
    if (declared.startsWith("http://") || declared.startsWith("https://")) return parse(declared);
    return parse(specUri.getScheme() + "://" + specUri.getAuthority() + (declared.startsWith("/") ? declared : ""));
  }

  private static URI url(URI base, String path, Map<String, String> query) {
    var text = new StringBuilder(base.toString().replaceAll("/+$", "")).append(path);
    var separator = '?';
    for (var entry : query.entrySet()) {
      text.append(separator)
          .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      separator = '&';
    }
    return URI.create(text.toString());
  }

  private static Optional<URI> parse(String text) {
    try {
      var uri = URI.create(text);
      if (uri.getHost() == null || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
        return Optional.empty();
      }
      return Optional.of(uri);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}

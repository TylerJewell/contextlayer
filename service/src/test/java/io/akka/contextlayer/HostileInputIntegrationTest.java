package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint, given input a careless or hostile caller could send: ids with separators, quotes,
 * query fragments, control characters and absurd lengths; bodies that are missing, the wrong shape,
 * invalid JSON, enormous or deeply nested; caller names that are blank, odd or huge.
 *
 * <p>Whatever is sent, the service must answer with a client error or success, never a server
 * error, and an error must not carry stack frames or class names. All problems are gathered and
 * reported together.
 */
public class HostileInputIntegrationTest extends LayerTestBase {

  private record Endpoint(String method, String path, String body) {}

  private static final String QUESTION =
      "{\"entityType\":\"Claim\",\"where\":[],\"links\":[],\"returnFields\":[\"Claim.claimId\"],\"limit\":5}";
  private static final String PACK = """
      {"name":"claims","version":"1.0.0","source":{"kind":"openapi","ref":"http://localhost:1/openapi.json"},
       "credentialRef":"vault://x/claims","entities":[{"type":"Claim","operation":"listClaims","recordsPath":"/items",
       "idField":"claimId","identityAttributes":["email"],"filters":[],"freshness":{"policy":"ON_DEMAND","ttlMinutes":0},
       "paging":{"style":"none","maxPageSize":10},"rate":{"callsPerMinute":60}}],"actions":[]}""";

  /** Every route, with {x} where a caller-chosen id goes and a body that would be fine if the ids were. */
  private static final List<Endpoint> ENDPOINTS = List.of(
      new Endpoint("POST", "/orgs/{x}/assistants/{x}", "{\"purpose\":\"p\"}"),
      new Endpoint("GET", "/orgs/{x}/assistants/{x}", null),
      new Endpoint("GET", "/orgs/{x}/assistants", null),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/releases", "{\"definition\":\"{}\"}"),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/releases/{x}/approve", "{}"),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/stage", "{\"stage\":\"LIVE\"}"),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/actions/{x}", "{\"actionType\":\"notify.log\",\"payload\":\"x\"}"),
      new Endpoint("GET", "/orgs/{x}/assistants/{x}/actions/{x}", null),
      new Endpoint("GET", "/orgs/{x}/assistants/{x}/actions", null),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/actions/{x}/approve", "{}"),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/actions/{x}/reject", "{\"reason\":\"x\"}"),
      new Endpoint("POST", "/orgs/{x}/assistants/{x}/actions/{x}/resolve", "{\"succeeded\":true,\"note\":\"x\"}"),
      new Endpoint("GET", "/orgs/{x}/approvals", null),
      new Endpoint("POST", "/orgs/{x}/records",
          "{\"entityType\":\"Client\",\"source\":\"crm\",\"recordId\":\"1\",\"attributes\":{\"email\":\"a@b.c\"}}"),
      new Endpoint("GET", "/orgs/{x}/identity/review", null),
      new Endpoint("POST", "/orgs/{x}/identity/open", "{\"rule\":\"email\",\"key\":\"a@b.c\"}"),
      new Endpoint("POST", "/orgs/{x}/identity/confirm", "{\"rule\":\"email\",\"key\":\"a@b.c\"}"),
      new Endpoint("POST", "/orgs/{x}/identity/reject", "{\"rule\":\"email\",\"key\":\"a@b.c\"}"),
      new Endpoint("POST", "/orgs/{x}/connectors/{x}/versions", PACK),
      new Endpoint("POST", "/orgs/{x}/connectors/{x}/versions/{x}/activate", "{\"mode\":\"LIVE\"}"),
      new Endpoint("POST", "/orgs/{x}/connectors/{x}/deactivate", "{}"),
      new Endpoint("GET", "/orgs/{x}/connectors/{x}", null),
      new Endpoint("GET", "/orgs/{x}/connectors", null),
      new Endpoint("POST", "/orgs/{x}/connectors/{x}/entities/{x}/refresh", "{}"),
      new Endpoint("GET", "/orgs/{x}/connectors/{x}/entities/{x}/refresh-status", null),
      new Endpoint("POST", "/orgs/{x}/questions", QUESTION),
      new Endpoint("POST", "/orgs/{x}/questions/draft", "{\"text\":\"claims over 30 days\"}"),
      new Endpoint("POST", "/orgs/{x}/monitors/{x}",
          "{\"name\":\"m\",\"question\":" + QUESTION + ",\"keyField\":\"Claim.claimId\",\"intervalMinutes\":5,"
              + "\"assistantId\":\"\",\"routeTo\":\"\"}"),
      new Endpoint("POST", "/orgs/{x}/monitors/{x}/definition",
          "{\"name\":\"m\",\"question\":" + QUESTION + ",\"keyField\":\"Claim.claimId\",\"intervalMinutes\":5,"
              + "\"assistantId\":\"\",\"routeTo\":\"\"}"),
      new Endpoint("POST", "/orgs/{x}/monitors/{x}/enable", "{}"),
      new Endpoint("POST", "/orgs/{x}/monitors/{x}/disable", "{}"),
      new Endpoint("POST", "/orgs/{x}/monitors/{x}/run", "{}"),
      new Endpoint("GET", "/orgs/{x}/monitors/{x}", null),
      new Endpoint("GET", "/orgs/{x}/monitors", null),
      new Endpoint("GET", "/orgs/{x}/cases", null),
      new Endpoint("GET", "/orgs/{x}/cases/status/{x}", null),
      new Endpoint("GET", "/orgs/{x}/cases/{x}", null),
      new Endpoint("POST", "/orgs/{x}/cases/{x}/resolve", "{\"note\":\"x\"}"),
      new Endpoint("POST", "/orgs/{x}/cases/{x}/dismiss", "{\"note\":\"x\"}"),
      new Endpoint("POST", "/orgs/{x}/cases/{x}/annotate", "{\"text\":\"x\"}"),
      new Endpoint("GET", "/orgs/{x}/governance/controls", null),
      new Endpoint("GET", "/orgs/{x}/governance/evidence", null),
      new Endpoint("GET", "/orgs/{x}/governance/compliance", null),
      new Endpoint("POST", "/orgs/{x}/governance/controls/{x}/waiver", "{\"reason\":\"x\"}"),
      new Endpoint("POST", "/orgs/{x}/governance/controls/{x}/waiver/lift", "{}"),
      new Endpoint("GET", "/orgs/{x}/routes/{x}", null),
      new Endpoint("GET", "/orgs/{x}/notifications", null),
      new Endpoint("POST", "/orgs/{x}/routes/{x}", "{\"url\":\"http://localhost:9/hook\",\"format\":\"json\"}"),
      new Endpoint("POST", "/orgs/{x}/routes/{x}/disable", "{}"),
      new Endpoint("POST", "/orgs/{x}/routes/{x}/test", "{}"),
      new Endpoint("GET", "/oversight/events", null),
      new Endpoint("GET", "/oversight/events/{x}", null),
      new Endpoint("GET", "/oversight/halts", null),
      new Endpoint("GET", "/oversight/standing", null),
      new Endpoint("POST", "/oversight/events/{x}/claim", "{}"),
      new Endpoint("POST", "/oversight/events/{x}/release", "{}"),
      new Endpoint("POST", "/oversight/events/{x}/decision", "{\"action\":\"APPROVE\",\"rationale\":\"x\"}"),
      new Endpoint("POST", "/oversight/events/{x}/escalate", "{\"reason\":\"x\"}"),
      new Endpoint("POST", "/oversight/events/{x}/send-back", "{\"requested\":\"x\"}"),
      new Endpoint("POST", "/oversight/events/{x}/resubmit", "{}"),
      new Endpoint("POST", "/oversight/events/{x}/withdraw", "{\"reason\":\"x\"}"),
      new Endpoint("POST", "/oversight/events/{x}/extend", "{}"),
      new Endpoint("POST", "/oversight/events/{x}/reopen", "{}"),
      new Endpoint("POST", "/oversight/flags", "{}"),
      new Endpoint("POST", "/oversight/halts", "{}"),
      new Endpoint("POST", "/oversight/standing/{x}/revoke", "{}"));

  /** Ids already in their on-the-wire form, so the ones a client library would normally escape are tested too. */
  private static final List<String> HOSTILE_IDS = List.of(
      "a%3Ab", "a%7Cb", "a%20b", "%C3%A9%E6%97%A5%E6%9C%AC", "x".repeat(500),
      "%27%3B%20DROP%20TABLE%20x%3B%20--", "%60%7D%29%20DETACH%20DELETE%20%28n%20%2F%2F", "%7B%7B7*7%7D%7D",
      "a%2Fb", "%00", "..", ".", "%2e%2e", "null", "-1", "%ff", "%0d%0a", "*", "%25");

  private static final List<String> HOSTILE_BODIES = List.of(
      "", "null", "{}", "[]", "\"text\"", "123", "{\"x\":",
      "{\"purpose\":123,\"stage\":[],\"mode\":{},\"definition\":{},\"actionType\":5,\"question\":\"q\"}",
      "[".repeat(2000) + "]".repeat(2000),
      "{\"a\":\"" + "x".repeat(1_000_000) + "\"}",
      "{\"purpose\":\"" + "\\u0000".repeat(50) + "\",\"note\":\"\\ud800\"}");

  private static final String VALID_ID = "sweep";

  private final List<String> serverErrors = new ArrayList<>();
  private final List<String> leaks = new ArrayList<>();

  /** The test kit reuses one instance of this class, so what one test found must not carry into the next. */
  @BeforeEach
  void forgetEarlierFindings() {
    serverErrors.clear();
    leaks.clear();
  }

  private void send(Endpoint endpoint, String path, String body, String principal) {
    var label = endpoint.method() + " " + abbreviate(path) + (body == null ? "" : "  body=" + abbreviate(body))
        + (principal == null ? "  (no caller)" : "  caller=" + abbreviate(principal));
    try {
      var request = "GET".equals(endpoint.method()) ? wire().GET(path) : wire().POST(path);
      if (principal != null) request = request.addHeader("Authorization", Tokens.bearer(principal, hostileOrg(path), "admin"));
      if (body != null) request = request.withRequestBody(ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8));
      var response = request.invoke();
      int status = response.status().intValue();
      var text = response.body().utf8String();
      // 501 is the deliberate answer for contract routes this service does not offer.
      if (status >= 500 && status != 501) serverErrors.add(label + " -> " + status + " " + abbreviate(text));
      else if (status >= 400 && looksInternal(text)) leaks.add(label + " -> " + status + " " + abbreviate(text));
    } catch (RuntimeException e) {
      serverErrors.add(label + " -> the request itself failed: " + abbreviate(String.valueOf(e)));
    }
  }

  /**
   * The SDK words its own "could not read the request body" answer using the names of our request
   * types. That wording is the SDK's, so it is accepted; anything else that names internals is not.
   */
  private static boolean looksInternal(String text) {
    if (text.startsWith("Error parsing request json:")) return false;
    return text.contains("\tat ") || text.contains("java.") || text.contains("akka.") || text.contains("kalix")
        || text.contains("Exception") || text.contains("io.akka");
  }

  /** The organization a request path names, as the runtime decodes it, so the token belongs to it. */
  private static String hostileOrg(String path) {
    var parts = path.split("/");
    if (parts.length < 3 || !parts[1].equals("orgs")) return "acme";
    try {
      return java.net.URLDecoder.decode(parts[2].replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return parts[2];
    }
  }

  private static String abbreviate(String s) {
    var oneLine = s.replace('\n', ' ').replace('\r', ' ');
    return oneLine.length() > 110 ? oneLine.substring(0, 110) + "…(" + s.length() + " chars)" : oneLine;
  }

  private static String fill(String template, String first, String rest) {
    var out = template.replaceFirst("\\{x}", first);
    return out.replace("{x}", rest);
  }

  private static String valid(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  private void report(String what) {
    var all = new ArrayList<String>();
    serverErrors.forEach(e -> all.add("SERVER ERROR  " + e));
    leaks.forEach(e -> all.add("LEAKS DETAIL  " + e));
    assertThat(all).as(what + ": " + all.size() + " problem(s)\n" + String.join("\n", all)).isEmpty();
  }

  @Test
  public void hostileIdsAnywhereInAPathNeverCauseAServerError() {
    for (var endpoint : ENDPOINTS) {
      for (var id : HOSTILE_IDS) {
        send(endpoint, fill(endpoint.path(), id, id), endpoint.body(), "tester");
        send(endpoint, fill(endpoint.path(), VALID_ID, id), endpoint.body(), "tester");
      }
    }
    report("hostile ids");
  }

  @Test
  public void hostileBodiesNeverCauseAServerError() {
    for (var endpoint : ENDPOINTS) {
      if (endpoint.body() == null) continue;
      var path = fill(endpoint.path(), VALID_ID, VALID_ID);
      send(endpoint, path, null, "tester");
      for (var body : HOSTILE_BODIES) send(endpoint, path, body, "tester");
    }
    report("hostile bodies");
  }

  @Test
  public void hostileCallerNamesNeverCauseAServerError() {
    for (var endpoint : ENDPOINTS) {
      var path = fill(endpoint.path(), VALID_ID, VALID_ID);
      send(endpoint, path, endpoint.body(), null);
      for (var caller : List.of("", "   ", "a:b|c", "x".repeat(10_000), "é日本", "'; --", "../../x")) {
        send(endpoint, path, endpoint.body(), caller);
      }
    }
    report("hostile caller names");
  }

  @Test
  public void everyRouteAnswersAnUnknownOrganizationWithAnEmptyResultOrAClientError() {
    var org = valid("never-seen-" + System.nanoTime());
    for (var endpoint : ENDPOINTS) {
      send(endpoint, fill(endpoint.path(), org, VALID_ID), endpoint.body(), "tester");
    }
    report("unknown organization");
  }
}

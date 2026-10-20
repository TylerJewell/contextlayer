package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import io.akka.contextlayer.api.CaseEndpoint;
import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.api.NotificationEndpoint;
import io.akka.contextlayer.api.OversightEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.api.OverviewEndpoint;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Who may call what. Every route is listed with the permission it needs; a test fails if an
 * endpoint gains a route this table does not know, so no route can be added without deciding who
 * may use it.
 */
public class AuthIntegrationTest extends LayerTestBase {

  private static final Map<String, Permission> ROUTES = new TreeMap<>();

  static {
    var base = "/v1/context";
    // reads
    for (var read : List.of(
        "GET " + base + "/assistants/{assistantId}", "GET " + base + "/assistants/{assistantId}/actions/{actionKey}",
        "GET " + base + "/assistants/{assistantId}/actions", "GET " + base + "/approvals",
        "GET " + base + "/connectors/{packName}", "GET " + base + "/identity/review",
        "GET " + base + "/connectors/{packName}/entities/{entityType}/refresh-status",
        "GET " + base + "/connectors", "GET " + base + "/assistants",
        "GET " + base + "/monitors", "GET " + base + "/monitors/{monitorId}",
        "GET " + base + "/cases", "GET " + base + "/cases/status/{status}", "GET " + base + "/cases/{caseId}")) {
      ROUTES.put(read, Permission.READ);
    }
    // building, feeding and asking
    for (var operate : List.of(
        "POST " + base + "/assistants/{assistantId}", "POST " + base + "/assistants/{assistantId}/releases",
        "POST " + base + "/assistants/{assistantId}/actions/{actionKey}",
        "POST " + base + "/connectors/{packName}/versions", "POST " + base + "/records",
        "POST " + base + "/questions", "POST " + base + "/questions/draft",
        "POST " + base + "/connectors/{packName}/entities/{entityType}/refresh",
        "POST " + base + "/monitors/{monitorId}", "POST " + base + "/monitors/{monitorId}/definition",
        "POST " + base + "/monitors/{monitorId}/enable", "POST " + base + "/monitors/{monitorId}/disable",
        "POST " + base + "/monitors/{monitorId}/run", "POST " + base + "/cases/{caseId}/annotate")) {
      ROUTES.put(operate, Permission.OPERATE);
    }
    // judging
    for (var review : List.of(
        "POST " + base + "/assistants/{assistantId}/actions/{actionKey}/resolve",
        "POST " + base + "/identity/open", "POST " + base + "/identity/confirm", "POST " + base + "/identity/reject",
        "POST " + base + "/cases/{caseId}/resolve", "POST " + base + "/cases/{caseId}/dismiss")) {
      ROUTES.put(review, Permission.REVIEW);
    }
    // authorizing
    for (var approve : List.of(
        "POST " + base + "/assistants/{assistantId}/releases/{releaseId}/approve",
        "POST " + base + "/assistants/{assistantId}/stage",
        "POST " + base + "/assistants/{assistantId}/actions/{actionKey}/approve",
        "POST " + base + "/assistants/{assistantId}/actions/{actionKey}/reject",
        "POST " + base + "/connectors/{packName}/versions/{hash}/activate",
        "POST " + base + "/connectors/{packName}/deactivate")) {
      ROUTES.put(approve, Permission.APPROVE);
    }
    ROUTES.put("POST " + base + "/streams/changes", Permission.OPERATE);
    ROUTES.put("GET " + base + "/streams/changes", Permission.READ);
    ROUTES.put("POST " + base + "/streams/watch", Permission.OPERATE);
    // data domains
    ROUTES.put("GET " + base + "/domains", Permission.READ);
    ROUTES.put("POST " + base + "/domains/{name}", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/domains/{name}/remove", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/join-rules", Permission.APPROVE);
    ROUTES.put("POST " + base + "/join-rules/revoke", Permission.ADMINISTER);
    // notifications
    ROUTES.put("GET " + base + "/routes/{name}", Permission.READ);
    ROUTES.put("GET " + base + "/notifications", Permission.READ);
    ROUTES.put("POST " + base + "/routes/{name}", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/routes/{name}/disable", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/routes/{name}/test", Permission.ADMINISTER);
    // governance
    for (var read : List.of(
        "GET " + base + "/governance/controls", "GET " + base + "/governance/evidence",
        "GET " + base + "/governance/compliance")) {
      ROUTES.put(read, Permission.READ);
    }
    ROUTES.put("GET " + base + "/governance/integrity", Permission.READ);
    ROUTES.put("GET " + base + "/governance/rte", Permission.READ);
    ROUTES.put("GET " + base + "/governance/documents", Permission.READ);
    ROUTES.put("GET " + base + "/governance/documents/{name}", Permission.READ);
    ROUTES.put("POST " + base + "/governance/documents/{name}", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/governance/ask", Permission.READ);
    ROUTES.put("GET " + base + "/governance/record-stores", Permission.READ);
    ROUTES.put("GET " + base + "/governance/controls/{controlId}/claims", Permission.READ);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/acceptance", Permission.APPROVE);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/acceptance/withdraw", Permission.APPROVE);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/assertion", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/documents", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/waiver", Permission.ADMINISTER);
    ROUTES.put("POST " + base + "/governance/controls/{controlId}/waiver/lift", Permission.ADMINISTER);
    // oversight
    for (var read : List.of("GET /v1/context/oversight/events", "GET /v1/context/oversight/events/{id}", "GET /v1/context/oversight/halts",
        "GET /v1/context/oversight/standing", "POST /v1/context/oversight/events/{id}/resubmit", "POST /v1/context/oversight/events/{id}/withdraw")) {
      ROUTES.put(read, Permission.READ);
    }
    for (var review : List.of(
        "POST /v1/context/oversight/events/{id}/claim", "POST /v1/context/oversight/events/{id}/release",
        "POST /v1/context/oversight/events/{id}/decision", "POST /v1/context/oversight/events/{id}/escalate",
        "POST /v1/context/oversight/events/{id}/send-back", "POST /v1/context/oversight/events/{id}/extend",
        "POST /v1/context/oversight/events/{id}/reopen", "POST /v1/context/oversight/flags", "POST /v1/context/oversight/halts",
        "POST /v1/context/oversight/standing/{id}/revoke")) {
      ROUTES.put(review, Permission.REVIEW);
    }
  }

  private static List<String> routesOf(Class<?>... endpoints) {
    var found = new ArrayList<String>();
    for (var endpoint : endpoints) {
      var prefix = endpoint.getAnnotation(HttpEndpoint.class).value();
      for (var m : endpoint.getDeclaredMethods()) {
        var get = m.getAnnotation(Get.class);
        var post = m.getAnnotation(Post.class);
        if (get != null) found.add("GET " + prefix + get.value());
        if (post != null) found.add("POST " + prefix + post.value());
      }
    }
    return found;
  }

  @Test
  public void everyRouteHasADeclaredPermission() {
    var actual = routesOf(ContextLayerEndpoint.class, OverviewEndpoint.class, MonitorEndpoint.class, CaseEndpoint.class,
        GovernanceEndpoint.class, OversightEndpoint.class, NotificationEndpoint.class, io.akka.contextlayer.api.StreamEndpoint.class,
        io.akka.contextlayer.api.DomainEndpoint.class);

    assertThat(ROUTES.keySet()).containsExactlyInAnyOrderElementsOf(actual);
  }

  @Test
  public void aRouteAnswers403ExactlyToRolesWithoutItsPermission() {
    var org = newOrg();
    var denied = new ArrayList<String>();
    for (var route : ROUTES.entrySet()) {
      for (var role : Role.values()) {
        int status = call(route.getKey(), org, Tokens.bearer("someone", org, role.claimName()));
        boolean allowed = role.permissions().contains(route.getValue());
        if (allowed && (status == 401 || status == 403)) denied.add(role + " refused on " + route.getKey() + " -> " + status);
        if (!allowed && status != 403) denied.add(role + " not refused on " + route.getKey() + " -> " + status);
      }
    }
    assertThat(denied).isEmpty();
  }

  @Test
  public void aRequestWithoutATokenNeverReachesARoute() {
    var org = newOrg();
    var reached = new ArrayList<String>();
    for (var route : ROUTES.keySet()) {
      int status = call(route, org, null);
      // The runtime itself turns a request with no bearer token away, with a 400, before any route runs.
      if (status != 400 && status != 401 && status != 403) reached.add(route + " -> " + status);
    }
    assertThat(reached).isEmpty();
  }

  @Test
  public void aRoleNameThatIsNotOursGrantsNothing() {
    var org = newOrg();

    assertThat(call("GET /v1/context/assistants", org, Tokens.bearer("x", org, "superuser", "root"))).isEqualTo(403);
  }

  @Test
  public void aTokenWithoutASubjectIsRefused() {
    var org = newOrg();
    var noSubject = "Bearer " + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
        + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"iss\":\"test-idp\",\"roles\":[\"admin\"],\"orgs\":[\"" + org + "\"]}").getBytes());

    assertThat(call("GET /v1/context/assistants", org, noSubject)).isEqualTo(401);
  }

  /** Fills the route's ids with a plain value and sends it with the given Authorization header. */
  private int call(String route, String org, String authorization) {
    var method = route.substring(0, route.indexOf(' '));
    var path = route.substring(route.indexOf(' ') + 1).replaceAll("\\{[A-Za-z]+\\}", "x");
    var request = "GET".equals(method) ? wire().GET(path) : wire().POST(path);
    if (authorization != null) request = request.addHeader("Authorization", authorization);
    if (!"GET".equals(method)) request = request.withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes());
    try {
      return request.withTimeout(java.time.Duration.ofSeconds(3)).invoke().status().intValue();
    } catch (Throwable timedOut) {
      // A stream that was let in does not finish, so there is no answer to wait for.
      if (path.endsWith("/streams/changes") && "GET".equals(method) || path.endsWith("/streams/watch")) return 200;
      throw timedOut;
    }
  }

}

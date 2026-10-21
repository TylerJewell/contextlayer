package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.DomainEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** Data domains: what a question may read depends on who asks, and linking two domains needs approval. */
public class DomainIntegrationTest extends LayerTestBase {

  private static final String ROOT = "root";

  private String token(String subject, String org, String role) {
    return Tokens.bearer(subject, org, role);
  }

  private akka.javasdk.http.StrictResponse<akka.util.ByteString> send(String org, String subject, String role, String path, Object body) {
    return wire().POST("/orgs/" + org + path).addHeader("Authorization", token(subject, org, role))
        .withRequestBody(body).invoke();
  }

  private String readerOf(String org, String subject, String role, String path, Object body) {
    return send(org, subject, role, path, body).body().utf8String();
  }

  /** claims for operators, wealth for approvers and carol; both defined by root. */
  private String governed() {
    var org = connectBoth(newOrg());
    define(org, "claims", List.of("Claim"), List.of("operator"), List.of());
    define(org, "wealth", List.of("Account"), List.of("approver"), List.of("carol"));
    return org;
  }

  private void define(String org, String name, List<String> types, List<String> roles, List<String> people) {
    var response = send(org, ROOT, "admin", "/domains/" + name, new DomainEndpoint.DomainRequest(name + " data", types, roles, people, "who reads " + name));
    assertThat(response.status().intValue()).as(response.body().utf8String()).isEqualTo(200);
  }

  private int ask(String org, String subject, String role, ContextLayerEndpoint.QuestionRequest question) {
    return send(org, subject, role, "/questions", question).status().intValue();
  }

  private ContextLayerEndpoint.QuestionRequest claimsOnly() {
    return new ContextLayerEndpoint.QuestionRequest("Claim", List.of(), List.of(), List.of("Claim.claimId"), 10);
  }

  private ContextLayerEndpoint.QuestionRequest accountsOnly() {
    return new ContextLayerEndpoint.QuestionRequest("Account", List.of(), List.of(), List.of("Account.accountId"), 10);
  }

  @Test
  public void aProjectWithNoDomainsAllowsEveryQuestion() {
    var org = connectBoth(newOrg());

    assertThat(ask(org, "alice", "operator", stalledClaimsWithWealth())).isEqualTo(200);
  }

  @Test
  public void aQuestionReadsOnlyTheDomainsItsAskerIsGrantedAndSaysWhy() {
    var org = governed();

    assertThat(ask(org, "alice", "operator", claimsOnly())).isEqualTo(200);
    var refused = send(org, "alice", "operator", "/questions", accountsOnly());
    assertThat(refused.status().intValue()).isEqualTo(403);
    assertThat(refused.body().utf8String()).contains("not granted the wealth data domain");
    assertThat(ask(org, "carol", "operator", accountsOnly())).as("carol is named in the wealth domain").isEqualTo(200);
  }

  @Test
  public void aTypeInNoDomainCannotBeReadOnceDomainsExist() {
    var org = connectBoth(newOrg());
    define(org, "claims", List.of("Claim"), List.of("operator"), List.of());

    var refused = send(org, "alice", "operator", "/questions", accountsOnly());

    assertThat(refused.status().intValue()).isEqualTo(403);
    assertThat(refused.body().utf8String()).contains("Account is in no data domain");
  }

  @Test
  public void everyDecisionIsEvidenceNamingWhoAskedAndWhatTheyWereRefused() {
    var org = governed();
    ask(org, "alice", "operator", claimsOnly());
    ask(org, "alice", "operator", accountsOnly());

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      JsonNode evidence = get("/orgs/" + org + "/governance/evidence?control=" + Controls.DOMAIN_ACCESS, JsonNode.class);
      assertThat(evidence.get("evidence")).extracting(e -> e.get("decision").asText()).containsExactlyInAnyOrder("PASS", "BLOCK");
      assertThat(evidence.get("evidence")).allSatisfy(e -> assertThat(e.get("actor").asText()).isEqualTo("alice"));
    });
  }

  @Test
  public void linkingTwoDomainsNeedsAnApprovalFromSomeoneWhoDidNotSetThemUp() {
    var org = governed();
    var question = stalledClaimsWithWealth();

    var refused = send(org, "carol", "operator", "/questions", question);
    assertThat(refused.status().intValue()).isEqualTo(403);
    assertThat(refused.body().utf8String()).contains("needs an approved join rule");

    var join = new DomainEndpoint.JoinRequest("claims", "wealth", "triage needs both");
    assertThat(send(org, ROOT, "admin", "/join-rules", join).status().intValue())
        .as("root set the domains up, so root cannot approve linking them").isEqualTo(403);
    assertThat(send(org, "bob", "approver", "/join-rules", new DomainEndpoint.JoinRequest("claims", "wealth", " ")).status().intValue())
        .as("a rationale is required").isNotEqualTo(200);
    assertThat(send(org, "bob", "approver", "/join-rules", join).status().intValue()).isEqualTo(200);

    assertThat(send(org, "carol", "operator", "/questions", question).status().intValue()).isEqualTo(200);
    assertThat(send(org, "alice", "operator", "/questions", question).status().intValue())
        .as("the rule links domains, it does not grant them").isEqualTo(403);

    send(org, ROOT, "admin", "/join-rules/revoke", new DomainEndpoint.JoinPair("wealth", "claims", "the triage ended"));
    assertThat(send(org, "carol", "operator", "/questions", question).status().intValue()).isEqualTo(403);
  }

  @Test
  public void aMonitorCanBeSetUpOnlyOverDataItsOwnerMayRead() {
    var org = governed();
    var monitor = new MonitorEndpoint.MonitorRequest("old-claims", claimsOnly(), "Claim.claimId", 60, "", "Team");
    var wealthy = new MonitorEndpoint.MonitorRequest("rich", accountsOnly(), "Account.accountId", 60, "", "Team");

    assertThat(wire().POST("/orgs/" + org + "/monitors/old-claims").addHeader("Authorization", token("alice", org, "operator"))
        .withRequestBody(monitor).invoke().status().intValue()).isEqualTo(200);
    assertThat(wire().POST("/orgs/" + org + "/monitors/rich").addHeader("Authorization", token("alice", org, "operator"))
        .withRequestBody(wealthy).invoke().status().intValue()).isEqualTo(403);
  }

  @Test
  public void aMonitorStopsWhenItsOwnerIsNoLongerGrantedWhatItReads() {
    var org = governed();
    var monitor = new MonitorEndpoint.MonitorRequest("old-claims", claimsOnly(), "Claim.claimId", 60, "", "Team");
    wire().POST("/orgs/" + org + "/monitors/old-claims").addHeader("Authorization", token("alice", org, "operator"))
        .withRequestBody(monitor).invoke();
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/monitors/old-claims", MonitorEndpoint.MonitorResponse.class).runCount()).isPositive());
    var runsBefore = get("/orgs/" + org + "/monitors/old-claims", MonitorEndpoint.MonitorResponse.class).runCount();

    // The claims domain is now readable by approvers only, so the operator who set the monitor up is not granted it.
    define(org, "claims", List.of("Claim"), List.of("approver"), List.of());
    wire().POST("/orgs/" + org + "/monitors/old-claims/run").addHeader("Authorization", token("alice", org, "operator"))
        .withRequestBody(Map.of()).invoke();

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      JsonNode evidence = get("/orgs/" + org + "/governance/evidence?control=" + Controls.DOMAIN_ACCESS, JsonNode.class);
      assertThat(evidence.get("evidence")).anySatisfy(e -> {
        assertThat(e.get("decision").asText()).isEqualTo("BLOCK");
        assertThat(e.get("actor").asText()).isEqualTo("alice");
        assertThat(e.get("authority").asText()).contains("watcher old-claims");
      });
    });
    assertThat(get("/orgs/" + org + "/monitors/old-claims", MonitorEndpoint.MonitorResponse.class).runCount())
        .as("a refused run is not recorded as a run").isEqualTo(runsBefore);
  }

  @Test
  public void aTypeCannotBeInTwoDomainsAndAnInvalidDomainIsRefused() {
    var org = governed();

    var clash = send(org, ROOT, "admin", "/domains/other", new DomainEndpoint.DomainRequest("", List.of("Claim"), List.of("viewer"), List.of(), "try"));
    assertThat(clash.status().intValue()).isEqualTo(400);
    assertThat(clash.body().utf8String()).contains("already in the claims");
    var bad = send(org, ROOT, "admin", "/domains/other", new DomainEndpoint.DomainRequest("", List.of(), List.of("boss"), List.of(), "try"));
    assertThat(bad.status().intValue()).isEqualTo(400);
  }

  @Test
  public void domainsAreListedToAnyoneWhoCanReadAndRemovedByAnAdministrator() {
    var org = governed();

    var listed = get("/orgs/" + org + "/domains", DomainEndpoint.DomainList.class);
    assertThat(listed.governed()).isTrue();
    assertThat(listed.domains()).extracting(DomainEndpoint.DomainView::name).containsExactly("claims", "wealth");

    assertThat(send(org, "alice", "operator", "/domains/wealth/remove", Map.of("rationale", "no longer needed")).status().intValue()).isEqualTo(403);
    var after = send(org, ROOT, "admin", "/domains/wealth/remove", Map.of("rationale", "no longer needed"));
    assertThat(after.status().intValue()).isEqualTo(200);
    assertThat(get("/orgs/" + org + "/domains", DomainEndpoint.DomainList.class).domains()).extracting(DomainEndpoint.DomainView::name)
        .containsExactly("claims");
  }
}

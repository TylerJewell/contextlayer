package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The evidence ledger is chained as it is written, and the chain can be checked and its head kept. */
public class IntegrityIntegrationTest extends LayerTestBase {

  private void say(String org, String who, String role, String path, Object body) {
    var response = wire().POST("/orgs/" + org + "/governance/controls/" + Controls.ACTION_APPROVAL + path)
        .addHeader("Authorization", Tokens.bearer(who, org, role)).withRequestBody(body).invoke();
    assertThat(response.status().intValue()).as(response.body().utf8String()).isEqualTo(200);
  }

  private GovernanceEndpoint.IntegrityReport integrity(String org, String query) {
    return get("/orgs/" + org + "/governance/integrity" + query, GovernanceEndpoint.IntegrityReport.class);
  }

  private GovernanceEndpoint.EvidenceExport evidence(String org) {
    return get("/orgs/" + org + "/governance/evidence", GovernanceEndpoint.EvidenceExport.class);
  }

  /**
   * Waits until every evidence record of the project is chained, and returns how many there are. Besides what a test
   * records, a project holds what the release records for it on first use (its gates) and the verdicts of the
   * controls a request passes, such as the caller's authorization.
   */
  private long chained(String org, long atLeast) {
    var count = new long[1];
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      long recorded = evidence(org).count();
      assertThat(recorded).isGreaterThanOrEqualTo(atLeast);
      assertThat(integrity(org, "").count()).isEqualTo(recorded);
      count[0] = recorded;
    });
    return count[0];
  }

  /** An acceptance, an assertion and an attachment, each a person's claim; the first two are decisions too. */
  private void recordSomeEvidence(String org) {
    say(org, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("accepted"));
    say(org, "erin", "admin", "/assertion", new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded",
        List.of(new GovernanceEndpoint.ExpectationView("procedure", 365)), "why"));
    say(org, "erin", "admin", "/documents", new GovernanceEndpoint.AttachRequest("procedure", "https://docs/p", java.time.LocalDate.now().toString()));
    awaitDecision(org, Controls.CONTROL_ACCEPTANCE);
    awaitDecision(org, Controls.EVIDENCE_ASSERTION);
  }

  /** A decision's evidence is derived from its human event a moment after the call returns. */
  private void awaitDecision(String org, String control) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(evidence(org).evidence()).anyMatch(e -> e.controlId().equals(control)
            && e.eventType().equals("HumanDecisionRecorded")));
  }

  @Test
  public void everyEvidenceRecordIsChainedAndTheChainChecksOut() {
    var org = newOrg();
    recordSomeEvidence(org);

    long count = chained(org, 5);

    var report = integrity(org, "");
    assertThat(report.scheme()).isEqualTo("HashChained");
    assertThat(report.intact()).as(report.problems().toString()).isTrue();
    assertThat(report.checked()).isEqualTo(count);
    assertThat(report.head()).startsWith("sha256:");
    assertThat(report.next()).isNull();
  }

  @Test
  public void theChainCanBeCheckedAStretchAtATime() {
    var org = newOrg();
    recordSomeEvidence(org);
    long count = chained(org, 5);

    var first = integrity(org, "?from=1&limit=2");
    var rest = integrity(org, "?from=" + first.next() + "&limit=1000");

    assertThat(first.checked()).isEqualTo(2);
    assertThat(first.next()).isEqualTo(3L);
    assertThat(rest.checked()).isGreaterThanOrEqualTo(count - 2);
    assertThat(rest.intact()).isTrue();
    assertThat(rest.next()).isNull();
  }

  @Test
  public void aNewProjectHoldsOnlyWhatTheReleaseAndItsFirstRequestRecordedForIt() {
    var org = newOrg();
    integrity(org, "");

    chained(org, 1);

    var report = integrity(org, "");
    assertThat(report.intact()).isTrue();
    assertThat(evidence(org).evidence()).extracting(GovernanceEndpoint.EvidenceItem::controlId)
        .isSubsetOf(Controls.CATALOG_VALID_AT_STARTUP, Controls.DRAFT_MATCHES_BUNDLE, Controls.SIGNED_CONTRACT_CONFORMANCE,
            Controls.CALLER_AUTHORIZATION, Controls.EVIDENCE_CHAIN_INTEGRITY);
  }

  @Test
  public void eachRecordStoreSaysWhatItDoesToMakeItsRecordsTamperEvident() {
    var org = newOrg();
    recordSomeEvidence(org);
    chained(org, 5);

    var stores = get("/orgs/" + org + "/governance/record-stores", GovernanceEndpoint.RecordStoreList.class).stores();

    assertThat(stores).extracting(GovernanceEndpoint.RecordStoreView::id)
        .containsExactly("evidence-ledger", "human-events", "control-claims", "trust-documents", "entity-graph");
    var byId = stores.stream().collect(java.util.stream.Collectors.toMap(GovernanceEndpoint.RecordStoreView::id, s -> s));
    assertThat(byId.get("evidence-ledger").scheme()).isEqualTo("HashChained");
    assertThat(byId.get("evidence-ledger").records()).isEqualTo(integrity(org, "").count());
    assertThat(byId.get("evidence-ledger").head()).isEqualTo(integrity(org, "").head());
    assertThat(byId.get("human-events").scheme()).isEqualTo("NoIntegrity");
    assertThat(byId.get("entity-graph").scheme()).isEqualTo("NoIntegrity");
  }

  @Test
  public void theProjectCanBeExportedInTheRuntimeEvidenceVocabulary() {
    var org = newOrg();
    recordSomeEvidence(org);
    chained(org, 5);

    var response = wire().GET("/orgs/" + org + "/governance/rte").addHeader("Authorization", Tokens.admin("auditor", "/orgs/" + org + "/x")).invoke();

    assertThat(response.status().intValue()).isEqualTo(200);
    assertThat(response.httpResponse().entity().getContentType().toString()).startsWith("text/turtle");
    var ttl = response.body().utf8String();
    assertThat(ttl).contains("@prefix rte:  <https://w3id.org/rte#>").contains("a rte:EvidenceAssertion")
        .contains("prov:wasAttributedTo ex:agent-dana").contains("prov:wasAttributedTo ex:agent-erin").contains("rte:hasCoverage rte:FullCoverage");
    assertThat(ttl).as("accepting is a decision under its own control, so it is runtime activity")
        .contains("a rte:HumanReview").contains("rte:ruleId \"" + Controls.CONTROL_ACCEPTANCE + "\"");
  }

  @Test
  public void aProjectsChainIsNotAnotherProjects() {
    var a = newOrg();
    var b = newOrg();
    say(a, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("accepted"));
    awaitDecision(a, Controls.CONTROL_ACCEPTANCE);
    chained(a, 1);
    chained(b, 0);

    assertThat(integrity(b, "").head()).isNotEqualTo(integrity(a, "").head());
    assertThat(evidence(b).evidence()).extracting(GovernanceEndpoint.EvidenceItem::controlId)
        .doesNotContain(Controls.CONTROL_ACCEPTANCE);
    assertThat(evidence(b).evidence()).extracting(GovernanceEndpoint.EvidenceItem::actor).doesNotContain("dana");
  }
}

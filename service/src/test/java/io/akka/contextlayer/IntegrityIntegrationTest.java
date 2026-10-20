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

  /** Three pieces of evidence: an acceptance, an assertion and an attachment. */
  private void recordSomeEvidence(String org) {
    say(org, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("accepted"));
    say(org, "erin", "admin", "/assertion", new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded",
        List.of(new GovernanceEndpoint.ExpectationView("procedure", 365)), "why"));
    say(org, "erin", "admin", "/documents", new GovernanceEndpoint.AttachRequest("procedure", "https://docs/p", java.time.LocalDate.now().toString()));
  }

  @Test
  public void everyEvidenceRecordIsChainedAndTheChainChecksOut() {
    var org = newOrg();
    recordSomeEvidence(org);

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(integrity(org, "").count()).isEqualTo(3));

    var report = integrity(org, "");
    assertThat(report.scheme()).isEqualTo("HashChained");
    assertThat(report.intact()).as(report.problems().toString()).isTrue();
    assertThat(report.checked()).isEqualTo(3);
    assertThat(report.head()).startsWith("sha256:");
    assertThat(report.next()).isNull();
  }

  @Test
  public void theChainCanBeCheckedAStretchAtATime() {
    var org = newOrg();
    recordSomeEvidence(org);
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(integrity(org, "").count()).isEqualTo(3));

    var first = integrity(org, "?from=1&limit=2");
    var rest = integrity(org, "?from=" + first.next() + "&limit=2");

    assertThat(first.checked()).isEqualTo(2);
    assertThat(first.next()).isEqualTo(3L);
    assertThat(rest.checked()).isEqualTo(1);
    assertThat(rest.intact()).isTrue();
    assertThat(rest.next()).isNull();
  }

  @Test
  public void aProjectWithNoEvidenceHasAnEmptyIntactChain() {
    var org = newOrg();

    var report = integrity(org, "");

    assertThat(report.count()).isZero();
    assertThat(report.head()).isNull();
    assertThat(report.intact()).isTrue();
  }

  @Test
  public void eachRecordStoreSaysWhatItDoesToMakeItsRecordsTamperEvident() {
    var org = newOrg();
    recordSomeEvidence(org);
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(integrity(org, "").count()).isEqualTo(3));

    var stores = get("/orgs/" + org + "/governance/record-stores", GovernanceEndpoint.RecordStoreList.class).stores();

    assertThat(stores).extracting(GovernanceEndpoint.RecordStoreView::id)
        .containsExactly("evidence-ledger", "human-events", "control-claims", "trust-documents", "entity-graph");
    var byId = stores.stream().collect(java.util.stream.Collectors.toMap(GovernanceEndpoint.RecordStoreView::id, s -> s));
    assertThat(byId.get("evidence-ledger").scheme()).isEqualTo("HashChained");
    assertThat(byId.get("evidence-ledger").records()).isEqualTo(3L);
    assertThat(byId.get("evidence-ledger").head()).isEqualTo(integrity(org, "").head());
    assertThat(byId.get("human-events").scheme()).isEqualTo("NoIntegrity");
    assertThat(byId.get("entity-graph").scheme()).isEqualTo("NoIntegrity");
  }

  @Test
  public void theProjectCanBeExportedInTheRuntimeEvidenceVocabulary() {
    var org = newOrg();
    recordSomeEvidence(org);
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(integrity(org, "").count()).isEqualTo(3));

    var response = wire().GET("/orgs/" + org + "/governance/rte").addHeader("Authorization", Tokens.admin("auditor", "/orgs/" + org + "/x")).invoke();

    assertThat(response.status().intValue()).isEqualTo(200);
    assertThat(response.httpResponse().entity().getContentType().toString()).startsWith("text/turtle");
    var ttl = response.body().utf8String();
    assertThat(ttl).contains("@prefix rte:  <https://w3id.org/rte#>").contains("a rte:EvidenceAssertion")
        .contains("prov:wasAttributedTo ex:agent-dana").contains("prov:wasAttributedTo ex:agent-erin").contains("rte:hasCoverage rte:FullCoverage");
    assertThat(ttl).as("the three records are claims, so none is runtime activity").doesNotContain("a rte:RuntimeRecord");
  }

  @Test
  public void aProjectsChainIsNotAnotherProjects() {
    var a = newOrg();
    var b = newOrg();
    say(a, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("accepted"));
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(integrity(a, "").count()).isEqualTo(1));

    assertThat(integrity(b, "").count()).isZero();
    assertThat(integrity(b, "").head()).isNull();
  }
}

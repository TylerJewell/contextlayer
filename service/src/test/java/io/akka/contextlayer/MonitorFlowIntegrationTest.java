package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.CaseEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Monitors end to end: a monitor created through the API starts its own first run (the timer
 * path), opens a case per new row, closes the case of a row that stops matching, and never closes
 * anything on a run that could not read every source.
 */
public class MonitorFlowIntegrationTest extends LayerTestBase {

  private MonitorEndpoint.MonitorRequest monitorRequest(String assistantId) {
    return new MonitorEndpoint.MonitorRequest("stalled-claims", stalledClaimsWithWealth(), "Claim.claimId", 60,
        assistantId, "Claims Team B");
  }

  private MonitorEndpoint.MonitorResponse monitor(String org) {
    return get("/orgs/" + org + "/monitors/m1", MonitorEndpoint.MonitorResponse.class);
  }

  private void awaitRunCount(String org, int count) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(
        () -> assertThat(monitor(org).runCount()).isGreaterThanOrEqualTo(count));
  }

  private void runNow(String org) {
    int before = monitor(org).runCount();
    post("alice", "/orgs/" + org + "/monitors/m1/run", Map.of(), MonitorEndpoint.RunStarted.class);
    awaitRunCount(org, before + 1);
  }

  private CaseEndpoint.CaseList cases(String org) {
    return get("/orgs/" + org + "/cases", CaseEndpoint.CaseList.class);
  }

  private void awaitCases(String org, int count) {
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(
        () -> assertThat(cases(org).items()).hasSize(count));
  }

  @Test
  public void aNewMonitorStartsItsOwnFirstRunAndOpensACaseWithItsEvidence() {
    var org = connectBoth(newOrg());

    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);

    awaitCases(org, 1);
    var summary = cases(org).items().get(0);
    assertThat(summary.key()).isEqualTo("1");
    assertThat(summary.status()).isEqualTo("OPEN");
    assertThat(summary.routeTo()).isEqualTo("Claims Team B");

    var detail = get("/orgs/" + org + "/cases/" + summary.caseId(), CaseEndpoint.CaseDetail.class);
    assertThat(detail.evidence()).containsEntry("Claim.claimId", "1").containsEntry("Account.balance", 412000);
    assertThat(detail.sourceNotes()).anyMatch(n -> n.startsWith("claims/Claim"));
    // The monitor opened it; the only other entry a new case gets is the note of whether its route was told.
    assertThat(detail.timeline().get(0).actor()).isEqualTo("monitor m1");
    assertThat(detail.timeline()).extracting(CaseEndpoint.TimelineEntry::actor).containsOnly("monitor m1", "notifications");
    assertThat(monitor(org).openRowKeys()).containsExactly("1");
  }

  @Test
  public void aRowThatKeepsMatchingNeverOpensASecondCase() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);
    awaitCases(org, 1);

    runNow(org);
    runNow(org);

    assertThat(cases(org).items()).hasSize(1);
    assertThat(monitor(org).runCount()).isGreaterThanOrEqualTo(3);
  }

  @Test
  public void aRowThatStopsMatchingMarksItsCaseButLeavesItOpenForAPerson() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);
    awaitCases(org, 1);
    var caseId = cases(org).items().get(0).caseId();

    claims.setBody(CLAIMS.replace("\"claimId\":\"1\",\"email\":\"pat@example.com\",\"status\":\"OPEN\"",
        "\"claimId\":\"1\",\"email\":\"pat@example.com\",\"status\":\"CLOSED\""));
    runNow(org);

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var detail = get("/orgs/" + org + "/cases/" + caseId, CaseEndpoint.CaseDetail.class);
      assertThat(detail.status()).isEqualTo("NO_LONGER_MATCHING");
      assertThat(detail.timeline()).extracting(CaseEndpoint.TimelineEntry::text)
          .anyMatch(t -> t.contains("no longer matches"));
    });
    assertThat(monitor(org).openRowKeys()).isEmpty();

    var closed = post("priya", "/orgs/" + org + "/cases/" + caseId + "/resolve",
        new CaseEndpoint.CloseRequest("handled by phone"), CaseEndpoint.CaseDetail.class);
    assertThat(closed.status()).isEqualTo("RESOLVED");
    assertThat(closed.timeline().get(closed.timeline().size() - 1).actor()).isEqualTo("priya");
  }

  @Test
  public void aRunThatCouldNotReadEverySourceNeverClosesACase() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);
    awaitCases(org, 1);

    // With no stored accounts and the wealth source down, the row cannot match, but only because
    // the data is missing.
    removeStoredAccounts(org);
    wealth.setDown(true);
    runNow(org);

    assertThat(monitor(org).lastRunNote()).startsWith("incomplete").contains("wealth/Account");
    assertThat(monitor(org).openRowKeys()).containsExactly("1");
    var detail = get("/orgs/" + org + "/cases/" + cases(org).items().get(0).caseId(), CaseEndpoint.CaseDetail.class);
    assertThat(detail.status()).isEqualTo("OPEN");

    wealth.setDown(false);
    runNow(org);
    assertThat(monitor(org).lastRunNote()).isEqualTo("complete");
    assertThat(cases(org).items()).hasSize(1);
  }

  @Test
  public void aSourceThatIsDownOnTheFirstRunOpensNothingAndSaysWhy() {
    var org = newOrg();
    wealth.setDown(true);
    connectBoth(org);
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);

    awaitRunCount(org, 1);
    assertThat(monitor(org).lastRunNote()).startsWith("incomplete");
    assertThat(cases(org).items()).isEmpty();

    wealth.setDown(false);
    runNow(org);
    awaitCases(org, 1);
  }

  @Test
  public void aDisabledMonitorRunsNothing() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);
    awaitCases(org, 1);
    post("alice", "/orgs/" + org + "/monitors/m1/disable", Map.of(), MonitorEndpoint.MonitorResponse.class);
    int runs = monitor(org).runCount();

    post("alice", "/orgs/" + org + "/monitors/m1/run", Map.of(), MonitorEndpoint.RunStarted.class);

    Awaitility.await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(monitor(org).runCount()).isEqualTo(runs));
    assertThat(monitor(org).enabled()).isFalse();
  }

  @Test
  public void aClosedCaseTakesNoFurtherNotesAndAnotherOrganizationCannotSeeIt() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1", monitorRequest(""), MonitorEndpoint.MonitorResponse.class);
    awaitCases(org, 1);
    var caseId = cases(org).items().get(0).caseId();

    post("priya", "/orgs/" + org + "/cases/" + caseId + "/annotate", new CaseEndpoint.AnnotateRequest("called the client"),
        CaseEndpoint.CaseDetail.class);
    post("priya", "/orgs/" + org + "/cases/" + caseId + "/dismiss", new CaseEndpoint.CloseRequest("duplicate"),
        CaseEndpoint.CaseDetail.class);

    assertThat(postStatus("priya", "/orgs/" + org + "/cases/" + caseId + "/annotate",
        new CaseEndpoint.AnnotateRequest("too late"))).isEqualTo(400);

    var other = newOrg();
    assertThat(wire().GET("/orgs/" + other + "/cases/" + caseId).addHeader("Authorization", Tokens.bearer("reader", other, "admin")).invoke().status().intValue()).isEqualTo(404);
    assertThat(cases(other).items()).isEmpty();
  }

  @Test
  public void aBadMonitorDefinitionIsRefusedWithItsProblems() {
    var org = newOrg();
    var bad = new MonitorEndpoint.MonitorRequest("m", stalledClaimsWithWealth(), "Claim.status", 0, "", "");
    assertThat(postStatus("alice", "/orgs/" + org + "/monitors/bad", bad)).isEqualTo(400);
  }
}

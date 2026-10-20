package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.governance.Controls;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The acceptance of a control and the assertion about its evidence are separate claims with their
 * own authors, and the compliance report says which controls fall between them.
 */
public class ControlClaimsIntegrationTest extends LayerTestBase {

  private static final String CONTROL = Controls.ACTION_APPROVAL;

  private String claimsPath(String org, String suffix) {
    return "/orgs/" + org + "/governance/controls/" + CONTROL + suffix;
  }

  private akka.javasdk.http.StrictResponse<akka.util.ByteString> send(String org, String who, String role, String path, Object body) {
    return wire().POST(path).addHeader("Authorization", Tokens.bearer(who, org, role)).withRequestBody(body).invoke();
  }

  private GovernanceEndpoint.ControlStanding standing(String org, String controlId) {
    var report = get("/orgs/" + org + "/governance/compliance", GovernanceEndpoint.ComplianceReport.class);
    return report.controls().stream().filter(c -> c.controlId().equals(controlId)).findFirst().orElseThrow();
  }

  private GovernanceEndpoint.Gaps gaps(String org) {
    return get("/orgs/" + org + "/governance/compliance", GovernanceEndpoint.ComplianceReport.class).gaps();
  }

  @Test
  public void aControlNobodyHasSaidAnythingAboutIsUnacceptedAndUnasserted() {
    var org = newOrg();

    var s = standing(org, CONTROL);

    assertThat(s.acceptance().accepted()).isFalse();
    assertThat(s.assertion().asserted()).isFalse();
    assertThat(s.coverage().coverage()).isEqualTo("NONE");
    assertThat(gaps(org).unaccepted()).contains(CONTROL);
  }

  @Test
  public void acceptingAControlRecordsWhoAndAtWhichVersionAndSaysNothingAboutEvidence() {
    var org = newOrg();

    var response = send(org, "dana", "approver", claimsPath(org, "/acceptance"), new GovernanceEndpoint.AcceptRequest("the system owner takes this on"));

    assertThat(response.status().intValue()).isEqualTo(200);
    var s = standing(org, CONTROL);
    assertThat(s.acceptance().accepted()).isTrue();
    assertThat(s.acceptance().current()).isTrue();
    assertThat(s.acceptance().by()).isEqualTo("dana");
    assertThat(s.assertion().asserted()).isFalse();
    assertThat(gaps(org).acceptedWithoutAssertion()).contains(CONTROL);
    assertThat(gaps(org).unaccepted()).doesNotContain(CONTROL);
  }

  @Test
  public void anAssertionHasItsOwnAuthorAndRestsOnTheAcceptanceItFollowed() {
    var org = newOrg();
    send(org, "dana", "approver", claimsPath(org, "/acceptance"), new GovernanceEndpoint.AcceptRequest("accepted"));

    var response = send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest(
        "HumanDecisionRecorded", List.of(new GovernanceEndpoint.ExpectationView("override procedure", 365)), "human reviews are the evidence"));

    assertThat(response.status().intValue()).as(response.body().utf8String()).isEqualTo(200);
    var s = standing(org, CONTROL);
    assertThat(s.acceptance().by()).isEqualTo("dana");
    assertThat(s.assertion().by()).isEqualTo("erin");
    assertThat(s.assertion().revision()).isEqualTo(1);
    assertThat(s.assertion().basisDigest()).isEqualTo(s.acceptance().catalogDigest());
    assertThat(s.coverage().coverage()).isEqualTo("PARTIAL");
    assertThat(s.coverage().unmet()).containsExactly("override procedure: none attached");
    assertThat(gaps(org).acceptedWithoutAssertion()).doesNotContain(CONTROL);
    assertThat(gaps(org).coveragePartial()).contains(CONTROL);
  }

  @Test
  public void attachingTheExpectedDocumentCompletesCoverageAndOnlyExpectedDocumentsAreTaken() {
    var org = newOrg();
    send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest(
        "HumanDecisionRecorded", List.of(new GovernanceEndpoint.ExpectationView("override procedure", 365)), "why"));

    var stray = send(org, "erin", "admin", claimsPath(org, "/documents"),
        new GovernanceEndpoint.AttachRequest("something else", "https://docs/x", LocalDate.now().toString()));
    assertThat(stray.status().intValue()).isEqualTo(400);
    assertThat(stray.body().utf8String()).contains("does not expect a document named something else");

    var old = send(org, "erin", "admin", claimsPath(org, "/documents"),
        new GovernanceEndpoint.AttachRequest("override procedure", "https://docs/op", LocalDate.now().minusDays(500).toString()));
    assertThat(old.status().intValue()).isEqualTo(200);
    assertThat(standing(org, CONTROL).coverage().coverage()).as("a document past its age limit does not count").isEqualTo("PARTIAL");

    send(org, "erin", "admin", claimsPath(org, "/documents"),
        new GovernanceEndpoint.AttachRequest("override procedure", "https://docs/op-v2", LocalDate.now().minusDays(10).toString()));
    assertThat(standing(org, CONTROL).coverage().coverage()).isEqualTo("FULL");
  }

  @Test
  public void aNewAssertionIsANewRevisionAndTheEarlierOneStaysInTheHistory() {
    var org = newOrg();
    send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), "first"));
    send(org, "frank", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), "second"));

    var detail = get(claimsPath(org, "/claims"), GovernanceEndpoint.ClaimsDetail.class);

    assertThat(detail.assertion().revision()).isEqualTo(2);
    assertThat(detail.assertion().by()).isEqualTo("frank");
    assertThat(detail.earlier()).singleElement().satisfies(a -> {
      assertThat(a.revision()).isEqualTo(1);
      assertThat(a.by()).isEqualTo("erin");
    });
  }

  @Test
  public void anAssertionNeedsAKnownEvidenceKindAndAReason() {
    var org = newOrg();

    var unknown = send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("MadeUpEvent", List.of(), "why"));
    var noReason = send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), " "));

    assertThat(unknown.status().intValue()).isEqualTo(400);
    assertThat(noReason.status().intValue()).isEqualTo(400);
  }

  @Test
  public void anAssertionWithNoEnforcingRecordsIsReportedAsAssertedWithoutEvidence() {
    var org = newOrg();
    send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), "why"));

    assertThat(gaps(org).assertedWithoutEvidence()).contains(CONTROL);
  }

  @Test
  public void whoSaidWhatIsItselfEvidenceButNeverCountsAsTheControlRunning() {
    var org = newOrg();
    send(org, "dana", "approver", claimsPath(org, "/acceptance"), new GovernanceEndpoint.AcceptRequest("accepted"));
    send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), "why"));

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var evidence = get("/orgs/" + org + "/governance/evidence?control=" + CONTROL, GovernanceEndpoint.EvidenceExport.class).evidence();
      assertThat(evidence).extracting(GovernanceEndpoint.EvidenceItem::eventType).contains("ControlAccepted", "EvidenceAsserted");
    });
    assertThat(standing(org, CONTROL).enforcing()).isZero();
  }

  @Test
  public void withdrawingAnAcceptanceLeavesTheAssertionAlone() {
    var org = newOrg();
    send(org, "dana", "approver", claimsPath(org, "/acceptance"), new GovernanceEndpoint.AcceptRequest("accepted"));
    send(org, "erin", "admin", claimsPath(org, "/assertion"), new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded", List.of(), "why"));

    send(org, "dana", "approver", claimsPath(org, "/acceptance/withdraw"), java.util.Map.of());

    var s = standing(org, CONTROL);
    assertThat(s.acceptance().accepted()).isFalse();
    assertThat(s.assertion().asserted()).isTrue();
  }
}

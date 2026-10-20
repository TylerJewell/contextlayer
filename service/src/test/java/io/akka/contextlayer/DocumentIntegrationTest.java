package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.TrustDocument;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The documents that back a control's evidence are kept by the project, by name, and one can back several controls. */
public class DocumentIntegrationTest extends LayerTestBase {

  private akka.javasdk.http.StrictResponse<akka.util.ByteString> send(String org, String path, Object body) {
    return wire().POST("/orgs/" + org + "/governance" + path).addHeader("Authorization", Tokens.bearer("erin", org, "admin"))
        .withRequestBody(body).invoke();
  }

  private void assertExpecting(String org, String control, String document) {
    var response = send(org, "/controls/" + control + "/assertion", new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded",
        List.of(new GovernanceEndpoint.ExpectationView(document, 365)), "why"));
    assertThat(response.status().intValue()).as(response.body().utf8String()).isEqualTo(200);
  }

  private GovernanceEndpoint.CoverageView coverage(String org, String control) {
    return get("/orgs/" + org + "/governance/controls/" + control + "/claims", GovernanceEndpoint.ClaimsDetail.class).coverage();
  }

  @Test
  public void aRegisteredDocumentKeepsItsVersionsAndTheDigestOfTheContentGiven() {
    var org = newOrg();
    var today = LocalDate.now().toString();

    var first = send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/op-v1", today, "step one, step two"));
    var second = send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/op-v2", today, null));

    assertThat(first.status().intValue()).isEqualTo(200);
    assertThat(second.status().intValue()).isEqualTo(200);
    var detail = get("/orgs/" + org + "/governance/documents/override-procedure", GovernanceEndpoint.DocumentDetail.class);
    assertThat(detail.versions()).extracting(GovernanceEndpoint.DocumentVersionView::version).containsExactly(1, 2);
    assertThat(detail.versions().get(0).digest()).isEqualTo(TrustDocument.digestOf("step one, step two"));
    assertThat(detail.versions().get(1).digest()).as("no content was given, so there is no digest to claim").isNull();
    assertThat(detail.newest().ref()).isEqualTo("https://docs/op-v2");
    assertThat(detail.versions().get(0).by()).isEqualTo("erin");
  }

  @Test
  public void aDocumentIsListedWithItsNewestVersion() {
    var org = newOrg();
    send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/op", LocalDate.now().toString(), null));
    send(org, "/documents/transfer-mapping", new GovernanceEndpoint.DocumentRequest("mapping", "https://docs/tm", LocalDate.now().toString(), null));

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var listed = get("/orgs/" + org + "/governance/documents", GovernanceEndpoint.DocumentList.class).documents();
      assertThat(listed).extracting(GovernanceEndpoint.DocumentSummary::name).containsExactly("override-procedure", "transfer-mapping");
      assertThat(listed.get(1).kind()).isEqualTo("mapping");
    });
  }

  @Test
  public void oneDocumentBacksEveryControlThatExpectsItByName() {
    var org = newOrg();
    assertExpecting(org, Controls.ACTION_APPROVAL, "override-procedure");
    assertExpecting(org, Controls.RUSHED_DECISIONS, "override-procedure");
    assertThat(coverage(org, Controls.ACTION_APPROVAL).coverage()).isEqualTo("PARTIAL");
    assertThat(coverage(org, Controls.RUSHED_DECISIONS).coverage()).isEqualTo("PARTIAL");

    send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/op", LocalDate.now().toString(), null));

    assertThat(coverage(org, Controls.ACTION_APPROVAL).coverage()).isEqualTo("FULL");
    assertThat(coverage(org, Controls.RUSHED_DECISIONS).coverage()).isEqualTo("FULL");
    var detail = get("/orgs/" + org + "/governance/controls/" + Controls.ACTION_APPROVAL + "/claims", GovernanceEndpoint.ClaimsDetail.class);
    assertThat(detail.documents()).singleElement().satisfies(d -> assertThat(d.name()).isEqualTo("override-procedure"));
  }

  @Test
  public void aLaterRegistrationOfAnOlderDateDoesNotDisplaceTheNewestVersion() {
    var org = newOrg();
    assertExpecting(org, Controls.ACTION_APPROVAL, "override-procedure");
    send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/new", LocalDate.now().toString(), null));

    send(org, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/old", LocalDate.now().minusDays(800).toString(), null));

    assertThat(coverage(org, Controls.ACTION_APPROVAL).coverage()).isEqualTo("FULL");
    assertThat(get("/orgs/" + org + "/governance/documents/override-procedure", GovernanceEndpoint.DocumentDetail.class).newest().ref())
        .isEqualTo("https://docs/new");
  }

  @Test
  public void aDocumentNeedsAKnownKindAWhereAndADateThatIsNotInTheFuture() {
    var org = newOrg();
    var today = LocalDate.now().toString();

    assertThat(send(org, "/documents/x", new GovernanceEndpoint.DocumentRequest("novel", "https://d", today, null)).status().intValue()).isEqualTo(400);
    assertThat(send(org, "/documents/x", new GovernanceEndpoint.DocumentRequest("policy", " ", today, null)).status().intValue()).isEqualTo(400);
    assertThat(send(org, "/documents/x", new GovernanceEndpoint.DocumentRequest("policy", "https://d", LocalDate.now().plusDays(30).toString(), null)).status().intValue()).isEqualTo(400);
    assertThat(send(org, "/documents/x", new GovernanceEndpoint.DocumentRequest("policy", "https://d", "last tuesday", null)).status().intValue()).isEqualTo(400);
  }

  @Test
  public void anUnknownDocumentIsNotFoundAndOneProjectsDocumentsAreNotAnothers() {
    var a = newOrg();
    var b = newOrg();
    send(a, "/documents/override-procedure", new GovernanceEndpoint.DocumentRequest("procedure", "https://docs/op", LocalDate.now().toString(), null));

    var missing = wire().GET("/orgs/" + b + "/governance/documents/override-procedure")
        .addHeader("Authorization", Tokens.bearer("erin", b, "admin")).invoke();

    assertThat(missing.status().intValue()).isEqualTo(404);
  }
}

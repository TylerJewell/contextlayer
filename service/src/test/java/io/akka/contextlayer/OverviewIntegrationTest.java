package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.api.OverviewEndpoint;
import io.akka.contextlayer.domain.AssistantStage;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The lists a console shows: connectors with their health, assistants, monitors. */
public class OverviewIntegrationTest extends LayerTestBase {

  private static final String PLAYBOOK =
      "{\"steps\":[{\"id\":\"note\",\"type\":\"annotate\",\"text\":\"ok\",\"next\":\"end\"}]}";

  @Test
  public void connectorsAreListedWithTheOutcomeOfTheirLastRead() {
    var org = connectBoth(newOrg());

    Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      // The question read both sources, which recorded their status.
      var list = get("/orgs/" + org + "/connectors", OverviewEndpoint.ConnectorList.class).items();
      assertThat(list).extracting(OverviewEndpoint.ConnectorSummary::name).containsExactly("claims", "wealth");
      assertThat(list).extracting(OverviewEndpoint.ConnectorSummary::mode).containsOnly("READ_ONLY");
      assertThat(list.get(0).entities()).hasSize(1);
      assertThat(list.get(0).entities().get(0).type()).isEqualTo("Claim");
      assertThat(list.get(0).entities().get(0).lastOutcome()).isEqualTo("OK");
      assertThat(list.get(0).entities().get(0).stored()).isEqualTo(3);
      assertThat(list.get(0).importedBy()).isEqualTo("alice");
    });
  }

  @Test
  public void aConnectorThatNeverReadShowsNever() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId"));

    Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var list = get("/orgs/" + org + "/connectors", OverviewEndpoint.ConnectorList.class).items();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).entities().get(0).lastOutcome()).isEqualTo("NEVER");
    });
  }

  @Test
  public void assistantsAreListedWithTheirStageAndReleaseCounts() {
    var org = newOrg();
    var base = "/orgs/" + org + "/assistants/claims-watch";
    post("alice", base, new ContextLayerEndpoint.CreateAssistantRequest("keep claims moving"),
        ContextLayerEndpoint.AssistantResponse.class);
    var release = post("alice", base + "/releases", new ContextLayerEndpoint.AddReleaseRequest(PLAYBOOK),
        ContextLayerEndpoint.ReleaseResponse.class);
    post("alice", base + "/releases/" + release.releaseId() + "/approve", new ContextLayerEndpoint.DecisionRequest("approved in test"),
        ContextLayerEndpoint.AssistantResponse.class);
    post("alice", base + "/stage", stageRequest(AssistantStage.SHADOW),
        ContextLayerEndpoint.AssistantResponse.class);

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var list = get("/orgs/" + org + "/assistants", OverviewEndpoint.AssistantList.class).items();
      assertThat(list).hasSize(1);
      var a = list.get(0);
      assertThat(a.assistantId()).isEqualTo("claims-watch");
      assertThat(a.owner()).isEqualTo("alice");
      assertThat(a.stage()).isEqualTo("SHADOW");
      assertThat(a.releaseCount()).isEqualTo(1);
      assertThat(a.approvedCount()).isEqualTo(1);
    });
    assertThat(get("/orgs/" + newOrg() + "/assistants", OverviewEndpoint.AssistantList.class).items()).isEmpty();
  }

  @Test
  public void monitorsAreListedWithTheirLastRun() {
    var org = connectBoth(newOrg());
    post("alice", "/orgs/" + org + "/monitors/m1",
        new MonitorEndpoint.MonitorRequest("stalled-claims", stalledClaimsWithWealth(), "Claim.claimId", 60, "", "Team B"),
        MonitorEndpoint.MonitorResponse.class);

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var list = get("/orgs/" + org + "/monitors", MonitorEndpoint.MonitorList.class).items();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).name()).isEqualTo("stalled-claims");
      assertThat(list.get(0).enabled()).isTrue();
      assertThat(list.get(0).runCount()).isGreaterThanOrEqualTo(1);
      assertThat(list.get(0).lastRunNote()).isEqualTo("complete");
    });

    post("alice", "/orgs/" + org + "/monitors/m1/disable", Map.of(), MonitorEndpoint.MonitorResponse.class);
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/monitors", MonitorEndpoint.MonitorList.class).items().get(0).enabled())
            .isFalse());
  }
}

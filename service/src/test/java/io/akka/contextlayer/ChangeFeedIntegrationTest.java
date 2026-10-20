package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.JsonSupport;
import io.akka.contextlayer.api.CaseEndpoint;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.api.StreamEndpoint;
import io.akka.contextlayer.domain.ConnectorPack;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Real-time reading of pushed data: a watcher is told as records change, a standing question
 * reports what its answer gains and loses, and a monitor set to run on change opens its case without
 * waiting for its schedule.
 */
public class ChangeFeedIntegrationTest extends LayerTestBase {

  private String connectedFeed() {
    var org = newOrg();
    var base = "/orgs/" + org + "/connectors/claims-feed";
    var pack = new ContextLayerEndpoint.PackRequest("claims-feed", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("stream", "akka-service://claims-system#entity_changes"), null,
        List.of(new ContextLayerEndpoint.EntityRequest("Claim", "", null, "claimId", List.of(), List.of(),
            new ContextLayerEndpoint.FreshnessRequest("STREAMED", 60), null, null)),
        List.of());
    var version = post("alice", base + "/versions", pack, ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate", activateRequest(ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/connectors", io.akka.contextlayer.api.OverviewEndpoint.ConnectorList.class).items())
            .hasSize(1));
    return org;
  }

  private void push(String org, String id, long version, String status) {
    post("feed", "/orgs/" + org + "/streams/changes", new StreamEndpoint.ChangeBatch(List.of(
        new StreamEndpoint.ChangeRequest("upsert", "Claim", id, version,
            Map.of("claimId", id, "status", status, "daysOpen", 40)))), StreamEndpoint.ChangeResult.class);
  }

  private ContextLayerEndpoint.QuestionRequest openClaims() {
    return new ContextLayerEndpoint.QuestionRequest("Claim",
        List.of(new ContextLayerEndpoint.ConditionRequest("status", "EQ", "OPEN")), List.of(),
        List.of("Claim.claimId", "Claim.status"), 100);
  }

  @Test
  public void aWatcherIsToldAsRecordsChangeAndOnlyAboutTheTypesItAskedFor() throws Exception {
    var org = connectedFeed();
    try (var watch = SseConnection.open(testKit.getPort(), "GET", "/v1/context/streams/changes?types=Claim",
        Tokens.bearer("watcher", org, "viewer"), null)) {

      push(org, "CL-1", 1, "OPEN");

      var event = watch.next(e -> e.contains("CL-1"), Duration.ofSeconds(15));
      assertThat(event).isNotNull();
      var change = JsonSupport.decodeJson(StreamEndpoint.ChangeEvent.class, event.getBytes());
      assertThat(change.op()).isEqualTo("upsert");
      assertThat(change.entityType()).isEqualTo("Claim");
      assertThat(change.pack()).isEqualTo("claims-feed");
    }
  }

  @Test
  public void aStandingQuestionGivesTheAnswerThenWhatChangesInIt() throws Exception {
    var org = connectedFeed();
    push(org, "CL-1", 1, "OPEN");
    var token = Tokens.bearer("watcher", org, "operator");
    try (var watch = SseConnection.open(testKit.getPort(), "POST", "/v1/context/streams/watch", token,
        JsonSupport.encodeToString(openClaims()))) {

      var first = watch.next(e -> e.contains("snapshot"), Duration.ofSeconds(20));
      assertThat(first).isNotNull();
      var snapshot = JsonSupport.decodeJson(StreamEndpoint.WatchEvent.class, first.getBytes());
      assertThat(snapshot.rows()).extracting(r -> r.get("Claim.claimId")).containsExactly("CL-1");

      push(org, "CL-2", 1, "OPEN");
      var added = watch.next(e -> e.contains("\"changed\""), Duration.ofSeconds(20));
      assertThat(added).isNotNull();
      var gained = JsonSupport.decodeJson(StreamEndpoint.WatchEvent.class, added.getBytes());
      assertThat(gained.added()).extracting(r -> r.get("Claim.claimId")).containsExactly("CL-2");
      assertThat(gained.total()).isEqualTo(2);

      push(org, "CL-1", 2, "CLOSED");
      var lost = watch.next(e -> e.contains("\"changed\""), Duration.ofSeconds(20));
      assertThat(lost).isNotNull();
      var gone = JsonSupport.decodeJson(StreamEndpoint.WatchEvent.class, lost.getBytes());
      assertThat(gone.removed()).extracting(r -> r.get("Claim.claimId")).containsExactly("CL-1");
    }
  }

  @Test
  public void aMonitorSetToRunOnChangeOpensItsCaseWithoutWaitingForItsSchedule() {
    var org = connectedFeed();
    var monitor = new MonitorEndpoint.MonitorRequest("open-claims", openClaims(), "Claim.claimId", 60, "", "Claims Team B", true);
    post("alice", "/orgs/" + org + "/monitors/open-claims", monitor, MonitorEndpoint.MonitorResponse.class);

    push(org, "CL-7", 1, "OPEN");

    Awaitility.await().atMost(30, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/cases", CaseEndpoint.CaseList.class).items())
            .extracting(CaseEndpoint.CaseSummary::key).contains("CL-7"));
  }

  @Test
  public void aMonitorNotSetToRunOnChangeDoesNotRunWhenDataChanges() throws Exception {
    var org = connectedFeed();
    var monitor = new MonitorEndpoint.MonitorRequest("open-claims", openClaims(), "Claim.claimId", 60, "", "Claims Team B");
    post("alice", "/orgs/" + org + "/monitors/open-claims", monitor, MonitorEndpoint.MonitorResponse.class);
    // Its first run, started when it was created, has to be over before the data changes.
    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/monitors/open-claims", MonitorEndpoint.MonitorResponse.class).runCount()).isPositive());

    push(org, "CL-7", 1, "OPEN");
    Thread.sleep(Duration.ofSeconds(12).toMillis());

    assertThat(get("/orgs/" + org + "/cases", CaseEndpoint.CaseList.class).items()).isEmpty();
  }
}

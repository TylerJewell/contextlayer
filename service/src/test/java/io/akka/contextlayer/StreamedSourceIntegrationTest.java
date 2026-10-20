package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.StreamEndpoint;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.EntityChange;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Records that a source pushes, rather than the service reading them: through the HTTP route a
 * third party would call, and through the service stream another Akka service publishes. A pushed
 * type has no operation to read, so these tests never script a source.
 */
public class StreamedSourceIntegrationTest extends LayerTestBase {

  private static ContextLayerEndpoint.PackRequest streamedPack(String freshnessPolicy, int minutes) {
    return new ContextLayerEndpoint.PackRequest("claims-feed", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("stream", "akka-service://claims-system#entity_changes"), null,
        List.of(new ContextLayerEndpoint.EntityRequest("Claim", "", null, "claimId", List.of("email"), List.of(),
            new ContextLayerEndpoint.FreshnessRequest(freshnessPolicy, minutes), null, null)),
        List.of());
  }

  /** The project the service stream consumer writes to, which is the one the deployment runs in. */
  private static final String DEPLOYMENT_PROJECT = "feed-project";

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings().withAdditionalConfig("contextlayer.project-id = \"" + DEPLOYMENT_PROJECT + "\"");
  }

  private String connectedFeed() {
    return connectedFeed(newOrg());
  }

  private String connectedFeed(String org) {
    var base = "/orgs/" + org + "/connectors/claims-feed";
    var version = post("alice", base + "/versions", streamedPack("STREAMED", 60), ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate", activateRequest(ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);
    // The connector list is a view and lags the activation; a change is taken only once it shows.
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/connectors", io.akka.contextlayer.api.OverviewEndpoint.ConnectorList.class).items())
            .hasSize(1));
    return org;
  }

  private static StreamEndpoint.ChangeRequest upsert(String id, long version, String status, int days) {
    return new StreamEndpoint.ChangeRequest("upsert", "Claim", id, version,
        Map.of("claimId", id, "status", status, "daysOpen", days, "email", id.toLowerCase() + "@example.com"));
  }

  private StreamEndpoint.ChangeResult push(String org, StreamEndpoint.ChangeRequest... changes) {
    return post("feed", "/orgs/" + org + "/streams/changes", new StreamEndpoint.ChangeBatch(List.of(changes)),
        StreamEndpoint.ChangeResult.class);
  }

  private ContextLayerEndpoint.AnswerResponse claims(String org) {
    return post("alice", "/orgs/" + org + "/questions",
        new ContextLayerEndpoint.QuestionRequest("Claim", List.of(), List.of(),
            List.of("Claim.claimId", "Claim.status", "Claim.daysOpen"), 100),
        ContextLayerEndpoint.AnswerResponse.class);
  }

  @Test
  public void pushedRecordsAnswerQuestionsAndTheAnswerSaysTheyWerePushed() {
    var org = connectedFeed();

    var result = push(org, upsert("CL-1", 1, "OPEN", 40), upsert("CL-2", 1, "CLOSED", 5));

    assertThat(result.applied()).isEqualTo(2);
    var answer = claims(org);
    assertThat(answer.rows()).extracting(r -> r.get("Claim.claimId")).containsExactlyInAnyOrder("CL-1", "CL-2");
    assertThat(answer.sources()).singleElement().satisfies(s -> {
      assertThat(s.freshness()).isEqualTo("STREAMED");
      assertThat(s.dataAsOf()).isPositive();
    });
  }

  @Test
  public void aChangeThatIsNotNewerThanTheOneHeldChangesNothing() {
    var org = connectedFeed();
    push(org, upsert("CL-1", 5, "OPEN", 40));

    var older = push(org, upsert("CL-1", 4, "CLOSED", 1));
    var again = push(org, upsert("CL-1", 5, "CLOSED", 1));
    var newer = push(org, upsert("CL-1", 6, "PENDING", 41));

    assertThat(older.alreadyHeld()).isEqualTo(1);
    assertThat(again.alreadyHeld()).isEqualTo(1);
    assertThat(newer.applied()).isEqualTo(1);
    assertThat(claims(org).rows()).singleElement().satisfies(r -> assertThat(r.get("Claim.status")).isEqualTo("PENDING"));
  }

  @Test
  public void aDeleteRemovesTheRecordUnlessTheStoreHoldsALaterVersion() {
    var org = connectedFeed();
    push(org, upsert("CL-1", 5, "OPEN", 40), upsert("CL-2", 5, "OPEN", 10));

    var stale = push(org, new StreamEndpoint.ChangeRequest("delete", "Claim", "CL-1", 4L, null));
    var real = push(org, new StreamEndpoint.ChangeRequest("delete", "Claim", "CL-2", 6L, null));

    assertThat(stale.alreadyHeld()).isEqualTo(1);
    assertThat(real.applied()).isEqualTo(1);
    assertThat(claims(org).rows()).extracting(r -> r.get("Claim.claimId")).containsExactly("CL-1");
  }

  @Test
  public void aChangeForATypeNoConnectorStreamsIsCountedAndLeftOut() {
    var org = connectedFeed();

    var result = push(org, new StreamEndpoint.ChangeRequest("upsert", "Policy", "P-1", 1L, Map.of("policyId", "P-1")));

    assertThat(result.noConnector()).isEqualTo(1);
    assertThat(result.applied()).isZero();
  }

  @Test
  public void changesThatAreNotUnderstoodAreNamedByPosition() {
    var org = connectedFeed();

    var result = push(org,
        new StreamEndpoint.ChangeRequest("upsert", "Claim", "CL-1", null, Map.of()),
        new StreamEndpoint.ChangeRequest("nonsense", "Claim", "CL-2", 1L, null),
        upsert("CL-3", 1, "OPEN", 3));

    assertThat(result.applied()).isEqualTo(1);
    assertThat(result.problems()).hasSize(2);
    assertThat(result.problems().get(0)).startsWith("change 0:");
    assertThat(result.problems().get(1)).startsWith("change 1:");
  }

  @Test
  public void aTypeThatNothingHasSentYetIsUnavailableNotEmpty() {
    var org = connectedFeed();

    var answer = claims(org);

    assertThat(answer.rows()).isEmpty();
    assertThat(answer.sources()).singleElement().satisfies(s -> {
      assertThat(s.freshness()).isEqualTo("UNAVAILABLE");
      assertThat(s.warnings()).anyMatch(w -> w.contains("nothing has been received"));
    });
  }

  @Test
  public void aHeartbeatShowsAQuietSourceIsAlive() {
    var org = connectedFeed();

    push(org, new StreamEndpoint.ChangeRequest("heartbeat", "Claim", null, null, null));

    assertThat(claims(org).sources()).singleElement().satisfies(s -> assertThat(s.freshness()).isEqualTo("STREAMED"));
  }

  @Test
  public void changesPublishedOnTheServiceStreamArriveLikeAnyOther() {
    var org = connectedFeed(adopt(DEPLOYMENT_PROJECT));
    var stream = testKit.getStreamIncomingMessages("claims-system", "entity_changes");

    stream.publish(new EntityChange.Upserted("Claim", "CL-9", 1, Map.of("claimId", "CL-9", "status", "OPEN", "daysOpen", 31)), "CL-9");

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(claims(org).rows()).extracting(r -> r.get("Claim.claimId")).containsExactly("CL-9"));
  }
}

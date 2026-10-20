package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.domain.ConnectorPack;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** A MAINTAINED type is read on a schedule that starts when its connector goes live, with no question asked. */
public class MaintainedRefreshIntegrationTest extends LayerTestBase {

  private ContextLayerEndpoint.PackRequest maintainedClaims() {
    return new ContextLayerEndpoint.PackRequest("claims", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("openapi", claims.specUrl()), "vault://integrations/claims",
        List.of(new ContextLayerEndpoint.EntityRequest("Claim", "listClaims", "/items", "claimId",
            List.of("email", "name", "dob"), List.of(), new ContextLayerEndpoint.FreshnessRequest("MAINTAINED", 30),
            new ContextLayerEndpoint.PagingRequest("none", 100, null, null, null, null),
            new ContextLayerEndpoint.RateRequest(600))),
        List.of());
  }

  @Test
  public void goingLiveStartsReadingTheTypeInTheBackground() {
    var org = newOrg();
    var base = "/orgs/" + org + "/connectors/claims";
    var version = post("alice", base + "/versions", maintainedClaims(), ContextLayerEndpoint.PackVersionResponse.class);

    post("bob", base + "/versions/" + version.hash() + "/activate", activateRequest(ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);

    Awaitility.await().atMost(30, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
      var status = get(base + "/entities/Claim/refresh-status", ContextLayerEndpoint.RefreshResponse.class);
      assertThat(status.outcome()).isEqualTo("OK");
      assertThat(status.stored()).isPositive();
    });
  }

  @Test
  public void aQuestionAnswersFromWhatTheBackgroundReadStoredAndSaysSo() {
    var org = newOrg();
    var base = "/orgs/" + org + "/connectors/claims";
    var version = post("alice", base + "/versions", maintainedClaims(), ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate", activateRequest(ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);
    Awaitility.await().atMost(30, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() ->
        assertThat(get(base + "/entities/Claim/refresh-status", ContextLayerEndpoint.RefreshResponse.class).outcome())
            .isEqualTo("OK"));

    // The connector list is a view that lags the activation.
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/connectors", io.akka.contextlayer.api.OverviewEndpoint.ConnectorList.class).items())
            .hasSize(1));

    var answer = post("alice", "/orgs/" + org + "/questions",
        new ContextLayerEndpoint.QuestionRequest("Claim", List.of(), List.of(), List.of("Claim.claimId"), 100),
        ContextLayerEndpoint.AnswerResponse.class);

    assertThat(answer.rows()).isNotEmpty();
    assertThat(answer.sources()).singleElement().satisfies(s -> assertThat(s.freshness()).isEqualTo("MAINTAINED"));
  }
}

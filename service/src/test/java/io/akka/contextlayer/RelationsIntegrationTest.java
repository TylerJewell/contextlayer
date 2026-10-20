package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.api.StreamEndpoint;
import io.akka.contextlayer.domain.ConnectorPack;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Relationships a source declares between its record types, followed by name in a question. Records
 * arrive through the route a third party would use, and in either order.
 */
public class RelationsIntegrationTest extends LayerTestBase {

  private String connected() {
    var org = newOrg();
    var base = "/orgs/" + org + "/connectors/book";
    var claim = new ContextLayerEndpoint.EntityRequest("Claim", "", null, "claimId", List.of(), List.of(),
        new ContextLayerEndpoint.FreshnessRequest("STREAMED", 60), null, null,
        List.of(new ContextLayerEndpoint.RelationRequest("HAS_ACCOUNT", "accountId", "Account")));
    var account = new ContextLayerEndpoint.EntityRequest("Account", "", null, "accountId", List.of(), List.of(),
        new ContextLayerEndpoint.FreshnessRequest("STREAMED", 60), null, null);
    var pack = new ContextLayerEndpoint.PackRequest("book", "1.0.0",
        new ContextLayerEndpoint.SourceRequest("stream", "akka-service://book#entity_changes"), null, List.of(claim, account), List.of());
    var version = post("alice", base + "/versions", pack, ContextLayerEndpoint.PackVersionResponse.class);
    post("bob", base + "/versions/" + version.hash() + "/activate", activateRequest(ConnectorPack.Mode.READ_ONLY),
        ContextLayerEndpoint.PackResponse.class);
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(get("/orgs/" + org + "/connectors", io.akka.contextlayer.api.OverviewEndpoint.ConnectorList.class).items()).hasSize(1));
    return org;
  }

  private void push(String org, String type, String id, long version, Map<String, Object> attributes) {
    post("feed", "/orgs/" + org + "/streams/changes", new StreamEndpoint.ChangeBatch(List.of(
        new StreamEndpoint.ChangeRequest("upsert", type, id, version, attributes))), StreamEndpoint.ChangeResult.class);
  }

  private ContextLayerEndpoint.AnswerResponse ask(String org, String base, String linked, String via, String direction, String field) {
    var link = new ContextLayerEndpoint.LinkRequest(linked, List.of(new ContextLayerEndpoint.ConditionRequest(field, "GT", 250000)), via, direction, 1);
    return post("alice", "/orgs/" + org + "/questions", new ContextLayerEndpoint.QuestionRequest(base, List.of(), List.of(link),
        List.of(base + "." + (base.equals("Claim") ? "claimId" : "accountId"), linked + "." + field), 100), ContextLayerEndpoint.AnswerResponse.class);
  }

  @Test
  public void aQuestionFollowsARelationshipTheSourceDeclaredEvenWhenTheTargetArrivesLater() {
    var org = connected();
    push(org, "Claim", "CL-1", 1, Map.of("claimId", "CL-1", "accountId", "W-1"));
    push(org, "Claim", "CL-2", 1, Map.of("claimId", "CL-2", "accountId", "W-2"));
    push(org, "Account", "W-1", 1, Map.of("accountId", "W-1", "balance", 412000));
    push(org, "Account", "W-2", 1, Map.of("accountId", "W-2", "balance", 9000));

    var answer = ask(org, "Claim", "Account", "HAS_ACCOUNT", "OUT", "balance");

    assertThat(answer.rows()).singleElement().satisfies(r -> {
      assertThat(r.get("Claim.claimId")).isEqualTo("CL-1");
      assertThat(r.get("Account.balance")).isEqualTo(412000);
    });
    assertThat(answer.sources()).extracting(ContextLayerEndpoint.SourceReportView::entityType).containsExactlyInAnyOrder("Claim", "Account");
    assertThat(answer.unconfirmedLeftOut()).isZero();
  }

  @Test
  public void theSameRelationshipCanBeFollowedBackwardsFromTheOtherType() {
    var org = connected();
    push(org, "Account", "W-1", 1, Map.of("accountId", "W-1", "balance", 412000));
    push(org, "Claim", "CL-1", 1, Map.of("claimId", "CL-1", "accountId", "W-1"));

    var link = new ContextLayerEndpoint.LinkRequest("Claim", List.of(), "HAS_ACCOUNT", "IN", 1);
    var answer = post("alice", "/orgs/" + org + "/questions", new ContextLayerEndpoint.QuestionRequest("Account", List.of(), List.of(link),
        List.of("Account.accountId", "Claim.claimId"), 100), ContextLayerEndpoint.AnswerResponse.class);

    assertThat(answer.rows()).singleElement().satisfies(r -> assertThat(r.get("Claim.claimId")).isEqualTo("CL-1"));
  }

  @Test
  public void aChangedRelationshipFieldMovesTheRelationship() {
    var org = connected();
    push(org, "Account", "W-1", 1, Map.of("accountId", "W-1", "balance", 412000));
    push(org, "Account", "W-2", 1, Map.of("accountId", "W-2", "balance", 9000));
    push(org, "Claim", "CL-1", 1, Map.of("claimId", "CL-1", "accountId", "W-1"));
    assertThat(ask(org, "Claim", "Account", "HAS_ACCOUNT", "OUT", "balance").rows()).hasSize(1);

    push(org, "Claim", "CL-1", 2, Map.of("claimId", "CL-1", "accountId", "W-2"));

    assertThat(ask(org, "Claim", "Account", "HAS_ACCOUNT", "OUT", "balance").rows()).isEmpty();
  }

  @Test
  public void aRelationshipNameNobodyDeclaredFindsNothingAndABadOneIsRefused() {
    var org = connected();
    push(org, "Claim", "CL-1", 1, Map.of("claimId", "CL-1", "accountId", "W-1"));
    push(org, "Account", "W-1", 1, Map.of("accountId", "W-1", "balance", 412000));

    assertThat(ask(org, "Claim", "Account", "OWNED_BY", "OUT", "balance").rows()).isEmpty();
    var bad = wire().POST("/orgs/" + org + "/questions").addHeader("Authorization", Tokens.admin("alice", "/orgs/" + org + "/x"))
        .withRequestBody(new ContextLayerEndpoint.QuestionRequest("Claim", List.of(),
            List.of(new ContextLayerEndpoint.LinkRequest("Account", List.of(), "X}]-() DETACH DELETE (n //", "OUT", 1)),
            List.of("Claim.claimId"), 10)).invoke();
    assertThat(bad.status().intValue()).isEqualTo(400);
  }
}

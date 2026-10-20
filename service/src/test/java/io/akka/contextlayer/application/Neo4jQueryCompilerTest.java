package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.domain.StructuredQuestion.Condition;
import io.akka.contextlayer.domain.StructuredQuestion.Link;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.util.List;
import org.junit.jupiter.api.Test;

class Neo4jQueryCompilerTest {

  private StructuredQuestion question() {
    return new StructuredQuestion("Claim",
        List.of(new Condition("status", Op.EQ, "OPEN ' OR 1=1 //")),
        List.of(new Link("Account", List.of(new Condition("balance", Op.GT, 250000)))),
        List.of("Claim.claimId", "Account.balance"), 50);
  }

  private Neo4jQueryCompiler.Compiled compile() {
    return Neo4jQueryCompiler.compile("org1", question(), IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS);
  }

  @Test
  void valuesTravelAsParametersAndNeverAppearInTheStatement() {
    var compiled = compile();
    assertThat(compiled.cypher()).doesNotContain("OPEN").doesNotContain("250000").doesNotContain("org1");
    assertThat(compiled.parameters()).containsValue("OPEN ' OR 1=1 //").containsEntry("orgId", "org1")
        .containsEntry("limit", 50);
  }

  @Test
  void everyNodePatternIsScopedToTheOrganization() {
    var cypher = compile().cypher();
    assertThat(cypher).contains("(a:Entity {orgId: $orgId, type: $t0})")
        .contains("(b1:Entity {orgId: $orgId, type: $t1})")
        .contains("(:Identity {orgId: $orgId, status: $identityStatus})");
  }

  @Test
  void linksGoOnlyThroughIdentitiesOfTheRequestedStatus() {
    assertThat(compile().parameters()).containsEntry("identityStatus", "CONFIRMED");
    var pending = Neo4jQueryCompiler.compile("org1", question(), IdentityCluster.Status.PENDING_REVIEW,
        Neo4jQueryCompiler.Output.BASE_IDS);
    assertThat(pending.parameters()).containsEntry("identityStatus", "PENDING_REVIEW");
    assertThat(pending.cypher()).contains("elementId(a) AS baseId");
  }

  @Test
  void theStatementContainsNoWriteClause() {
    assertThat(compile().cypher().toUpperCase())
        .doesNotContain("CREATE").doesNotContain("MERGE").doesNotContain("DELETE")
        .doesNotContain(" SET ").doesNotContain("REMOVE").doesNotContain("CALL");
  }

  @Test
  void returnColumnsUseTheQuestionNames() {
    assertThat(compile().cypher()).contains("a.`claimId` AS `Claim.claimId`").contains("b1.`balance` AS `Account.balance`");
  }

  @Test
  void aQuestionThatFailsValidationIsNeverCompiled() {
    var bad = new StructuredQuestion("Claim) DETACH DELETE (n", List.of(), List.of(), List.of("Claim.a"), 10);
    assertThatThrownBy(() -> Neo4jQueryCompiler.compile("org1", bad, IdentityCluster.Status.CONFIRMED,
        Neo4jQueryCompiler.Output.ROWS)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reservedSourceFieldNamesMapToTheirRenamedProperties() {
    var q = new StructuredQuestion("Claim", List.of(new Condition("type", Op.EQ, "x")), List.of(),
        List.of("Claim.id"), 10);
    var cypher = Neo4jQueryCompiler.compile("o", q, IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS).cypher();
    assertThat(cypher).contains("a.`src_type` =").contains("a.`src_id` AS `Claim.id`");
  }

  @Test
  void aRelationshipLinkFollowsTheNamedEdgeAsAParameterWithinTheOrganization() {
    var q = new StructuredQuestion("Claim", List.of(),
        List.of(new StructuredQuestion.Link("Account", List.of(new Condition("balance", Op.GT, 250000)), "HAS_ACCOUNT",
            StructuredQuestion.Direction.OUT, 2)),
        List.of("Claim.claimId", "Account.balance"), 10);

    var compiled = Neo4jQueryCompiler.compile("org1", q, IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS);

    assertThat(compiled.cypher()).contains("MATCH (a)-[:REL*1..2 {orgId: $orgId, name: $r1}]->(b1:Entity {orgId: $orgId, type: $t1})")
        .doesNotContain("HAS_ACCOUNT").doesNotContain("MEMBER_OF");
    assertThat(compiled.parameters()).containsEntry("r1", "HAS_ACCOUNT").containsEntry("orgId", "org1");
  }

  @Test
  void followingARelationshipBackwardsPointsTheArrowTheOtherWay() {
    var q = new StructuredQuestion("Account", List.of(),
        List.of(new StructuredQuestion.Link("Claim", List.of(), "HAS_ACCOUNT", StructuredQuestion.Direction.IN, 1)),
        List.of("Account.accountId", "Claim.claimId"), 10);

    var cypher = Neo4jQueryCompiler.compile("org1", q, IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS).cypher();

    assertThat(cypher).contains("MATCH (a)<-[:REL*1..1 {orgId: $orgId, name: $r1}]-(b1:Entity");
  }

  @Test
  void aRelationshipNameThatIsNotAPlainNameIsNeverCompiled() {
    var q = new StructuredQuestion("Claim", List.of(),
        List.of(new StructuredQuestion.Link("Account", List.of(), "X}]-() DETACH DELETE (n //", StructuredQuestion.Direction.OUT, 1)),
        List.of("Claim.claimId"), 10);

    assertThatThrownBy(() -> Neo4jQueryCompiler.compile("org1", q, IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

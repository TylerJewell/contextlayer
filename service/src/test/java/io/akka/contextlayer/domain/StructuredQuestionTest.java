package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.domain.StructuredQuestion.Condition;
import io.akka.contextlayer.domain.StructuredQuestion.Link;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredQuestionTest {

  private StructuredQuestion valid() {
    return new StructuredQuestion("Claim",
        List.of(new Condition("status", Op.EQ, "OPEN"), new Condition("daysOpen", Op.GT, 30)),
        List.of(new Link("Account", List.of(new Condition("balance", Op.GT, 250000)))),
        List.of("Claim.claimId", "Account.balance"), 100);
  }

  @Test
  void aWellFormedQuestionIsAccepted() {
    assertThat(valid().validate()).isEmpty();
  }

  @Test
  void typesAndFieldsCannotCarryQueryText() {
    var q = new StructuredQuestion("Claim) DETACH DELETE (n",
        List.of(new Condition("status` = 1 OR 1=1 //", Op.EQ, "x")),
        List.of(), List.of("Claim.claimId"), 10);
    assertThat(q.validate()).isNotEmpty();
  }

  @Test
  void conditionValuesMustBeSimple() {
    var q = new StructuredQuestion("Claim", List.of(new Condition("status", Op.EQ, List.of("a"))),
        List.of(), List.of("Claim.claimId"), 10);
    assertThat(q.validate()).anyMatch(e -> e.contains("string, number or boolean"));
  }

  @Test
  void returnFieldsMustBelongToTypesInTheQuestion() {
    var q = new StructuredQuestion("Claim", List.of(), List.of(), List.of("Account.balance"), 10);
    assertThat(q.validate()).anyMatch(e -> e.contains("Account.balance"));
  }

  @Test
  void everyPartIsBounded() {
    var many = java.util.Collections.nCopies(StructuredQuestion.MAX_CONDITIONS + 1, new Condition("a", Op.EQ, 1));
    assertThat(new StructuredQuestion("Claim", many, List.of(), List.of("Claim.a"), 10).validate())
        .anyMatch(e -> e.contains("at most"));
    assertThat(new StructuredQuestion("Claim", List.of(), List.of(), List.of("Claim.a"), 0).validate())
        .anyMatch(e -> e.contains("limit"));
    assertThat(new StructuredQuestion("Claim", List.of(), List.of(), List.of("Claim.a"),
        StructuredQuestion.MAX_LIMIT + 1).validate()).anyMatch(e -> e.contains("limit"));
    var links = java.util.Collections.nCopies(StructuredQuestion.MAX_LINKS + 1, new Link("Account", List.of()));
    assertThat(new StructuredQuestion("Claim", List.of(), links, List.of("Claim.a"), 10).validate())
        .anyMatch(e -> e.contains("links"));
  }

  @Test
  void aQuestionNeedsSomethingToReturn() {
    assertThat(new StructuredQuestion("Claim", List.of(), List.of(), List.of(), 10).validate())
        .anyMatch(e -> e.contains("returnFields"));
  }

  private static StructuredQuestion following(String via, StructuredQuestion.Direction direction, int depth) {
    return new StructuredQuestion("Claim", List.of(),
        List.of(new StructuredQuestion.Link("Account", List.of(), via, direction, depth)), List.of("Claim.claimId"), 10);
  }

  @Test
  void aLinkCanFollowANamedRelationshipForOneToThreeSteps() {
    assertThat(following("HAS_ACCOUNT", StructuredQuestion.Direction.OUT, 1).validate()).isEmpty();
    assertThat(following("HAS_ACCOUNT", StructuredQuestion.Direction.IN, 3).validate()).isEmpty();
    assertThat(following("HAS_ACCOUNT", StructuredQuestion.Direction.OUT, 4).validate()).anyMatch(e -> e.contains("depth must be between 1 and 3"));
    assertThat(following("HAS ACCOUNT) DETACH DELETE (n", StructuredQuestion.Direction.OUT, 1).validate()).anyMatch(e -> e.contains("via must be a relationship name"));
  }

  @Test
  void aLinkByIdentityHasNoSteps() {
    var q = new StructuredQuestion("Claim", List.of(),
        List.of(new StructuredQuestion.Link("Account", List.of(), "", StructuredQuestion.Direction.OUT, 2)), List.of("Claim.claimId"), 10);

    assertThat(q.validate()).anyMatch(e -> e.contains("depth applies only to a relationship"));
    assertThat(new StructuredQuestion.Link("Account", List.of()).byIdentity()).isTrue();
  }

  @Test
  void aLinkReadFromAnOlderMonitorDefaultsToIdentity() {
    var link = new StructuredQuestion.Link("Account", List.of(), null, null, 0);

    assertThat(link.byIdentity()).isTrue();
    assertThat(link.direction()).isEqualTo(StructuredQuestion.Direction.OUT);
    assertThat(link.depth()).isEqualTo(1);
  }
}

package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainConfigTest {

  private static DataDomain domain(String name, List<String> types, List<String> roles, List<String> people) {
    return new DataDomain(name, "", types, roles, people, "root", 1);
  }

  private static DomainConfig governed() {
    return DomainConfig.empty()
        .with(domain("claims", List.of("Claim"), List.of("operator"), List.of()))
        .with(domain("wealth", List.of("Account"), List.of("approver"), List.of("carol")));
  }

  @Test
  void aProjectWithNoDomainsAllowsEveryQuestion() {
    var access = DomainConfig.empty().check("anyone", Set.of(), List.of("Claim", "Account"));

    assertThat(access.allowed()).isTrue();
    assertThat(DomainConfig.empty().readableTypes("anyone", Set.of())).isEmpty();
  }

  @Test
  void aTypeInADomainIsReadByWhoeverTheDomainAdmitsByRoleOrByName() {
    var config = governed();

    assertThat(config.check("alice", Set.of("operator"), List.of("Claim")).allowed()).isTrue();
    assertThat(config.check("carol", Set.of("viewer"), List.of("Account")).allowed()).isTrue();
    var refused = config.check("alice", Set.of("operator"), List.of("Account"));
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.problems()).singleElement().asString().contains("not granted the wealth data domain");
  }

  @Test
  void aRoleIsMatchedByWhatTheCallerIsNotWhatTheyMightAlsoBeAbleToDo() {
    // An administrator is not an operator unless the domain says so.
    assertThat(governed().check("root", Set.of("admin"), List.of("Claim")).allowed()).isFalse();
  }

  @Test
  void onceDomainsExistATypeInNoneOfThemCannotBeRead() {
    var refused = governed().check("alice", Set.of("operator"), List.of("Policy"));

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.problems()).singleElement().asString().contains("Policy is in no data domain");
  }

  @Test
  void aQuestionAcrossTwoDomainsNeedsAJoinRuleForThatPairInEitherOrder() {
    var both = Set.of("operator", "approver");
    var config = governed();

    var refused = config.check("alice", both, List.of("Claim", "Account"));
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.problems()).singleElement().asString().contains("join rule");

    var joined = config.withJoin(new DomainConfig.JoinRule("claims", "wealth", "bob", "needed for triage", 1));
    assertThat(joined.check("alice", both, List.of("Claim", "Account")).allowed()).isTrue();
    assertThat(joined.check("alice", both, List.of("Account", "Claim")).allowed()).isTrue();
  }

  @Test
  void aJoinRuleDoesNotGrantReadingEitherDomain() {
    var joined = governed().withJoin(new DomainConfig.JoinRule("claims", "wealth", "bob", "needed", 1));

    assertThat(joined.check("alice", Set.of("operator"), List.of("Claim", "Account")).allowed()).isFalse();
  }

  @Test
  void removingADomainDropsItsJoinRules() {
    var joined = governed().withJoin(new DomainConfig.JoinRule("claims", "wealth", "bob", "needed", 1));

    assertThat(joined.without("wealth").joins()).isEmpty();
  }

  @Test
  void readableTypesAreThoseOfTheDomainsTheCallerIsAdmittedTo() {
    assertThat(governed().readableTypes("alice", Set.of("operator"))).contains(Set.of("Claim"));
    assertThat(governed().readableTypes("nobody", Set.of("viewer"))).contains(Set.of());
  }

  @Test
  void aTypeBelongsToOneDomain() {
    var clash = domain("other", List.of("Claim"), List.of("viewer"), List.of());

    assertThat(governed().conflictFor(clash)).hasValueSatisfying(m -> assertThat(m).contains("already in the claims"));
    assertThat(governed().conflictFor(domain("claims", List.of("Claim", "Note"), List.of("viewer"), List.of()))).isEmpty();
  }

  @Test
  void aDomainNeedsNamedTypesAndSomeoneWhoMayReadIt() {
    assertThat(domain("Bad Name", List.of(), List.of(), List.of()).validate())
        .anyMatch(e -> e.contains("name")).anyMatch(e -> e.contains("entity type")).anyMatch(e -> e.contains("who may read"));
    assertThat(domain("ok", List.of("lower"), List.of("boss"), List.of("system:x")).validate())
        .anyMatch(e -> e.contains("capital")).anyMatch(e -> e.contains("not a role")).anyMatch(e -> e.contains("subject"));
    assertThat(domain("ok", List.of("Claim"), List.of("Operator"), List.of()).validate()).isEmpty();
  }
}

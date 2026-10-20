package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.governance.ControlClaims.Assertion;
import io.akka.contextlayer.governance.ControlClaims.Coverage;
import io.akka.contextlayer.governance.ControlClaims.Expectation;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ControlClaimsTest {

  private static final long NOW = LocalDate.parse("2026-10-19").atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
  private static final Function<String, Optional<String>> NO_DOCUMENTS = name -> Optional.empty();

  private static Assertion assertion(int revision, Expectation... expects) {
    return new Assertion(revision, "HumanDecisionRecorded", List.of(expects), "compliance", "idp=x;roles=admin", "why", "digest-1", NOW);
  }

  /** The project's documents, by name, each with the date it was last current. */
  private static Function<String, Optional<String>> documents(Map<String, String> asOf) {
    return name -> Optional.ofNullable(asOf.get(name));
  }

  @Test
  void withNoAssertionNothingIsClaimedAndCoverageIsNone() {
    var result = ControlClaims.empty().coverage(NOW, NO_DOCUMENTS);

    assertThat(result.coverage()).isEqualTo(Coverage.NONE);
    assertThat(result.unmet()).singleElement().asString().contains("no assertion");
  }

  @Test
  void anAssertionThatExpectsNothingMoreThanTheRecordsIsFullyCovered() {
    var claims = ControlClaims.empty().withAssertion(assertion(1));

    assertThat(claims.coverage(NOW, NO_DOCUMENTS).coverage()).isEqualTo(Coverage.FULL);
  }

  @Test
  void anExpectedDocumentThatIsMissingLeavesCoveragePartialAndSaysWhich() {
    var claims = ControlClaims.empty().withAssertion(assertion(1, new Expectation("override procedure", 365)));

    var result = claims.coverage(NOW, NO_DOCUMENTS);

    assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
    assertThat(result.unmet()).containsExactly("override procedure: none attached");
  }

  @Test
  void aDocumentOlderThanItsLimitDoesNotCountButANewerOneDoes() {
    var claims = ControlClaims.empty().withAssertion(assertion(1, new Expectation("override procedure", 365)));

    var stale = claims.coverage(NOW, documents(Map.of("override procedure", "2025-01-01")));
    assertThat(stale.coverage()).isEqualTo(Coverage.PARTIAL);
    assertThat(stale.unmet()).singleElement().asString().contains("last current 2025-01-01").contains("365 days");

    var current = claims.coverage(NOW, documents(Map.of("override procedure", "2026-06-01")));
    assertThat(current.coverage()).isEqualTo(Coverage.FULL);
  }

  @Test
  void oneDocumentCanBackSeveralControlsBecauseItIsJudgedByNameAlone() {
    var one = ControlClaims.empty().withAssertion(assertion(1, new Expectation("override procedure", 365)));
    var other = ControlClaims.empty().withAssertion(assertion(1, new Expectation("override procedure", 90), new Expectation("halt test", 30)));
    var project = documents(Map.of("override procedure", "2026-09-01"));

    assertThat(one.coverage(NOW, project).coverage()).isEqualTo(Coverage.FULL);
    assertThat(other.coverage(NOW, project).unmet()).containsExactly("halt test: none attached");
  }

  @Test
  void aNewAssertionReplacesTheLastAndKeepsItInTheHistory() {
    var claims = ControlClaims.empty().withAssertion(assertion(1)).withAssertion(assertion(2));

    assertThat(claims.assertion().revision()).isEqualTo(2);
    assertThat(claims.earlier()).extracting(Assertion::revision).containsExactly(1);
  }

  @Test
  void anAcceptanceIsOfOneVersionOfTheCatalogAndGoesStaleWhenItChanges() {
    var claims = ControlClaims.empty().withAcceptance(new ControlClaims.Acceptance("owner", "idp=x", "taken on", "digest-1", NOW));

    assertThat(claims.acceptanceCurrent("digest-1")).isTrue();
    assertThat(claims.acceptanceCurrent("digest-2")).isFalse();
    assertThat(claims.withoutAcceptance().accepted()).isFalse();
  }

  @Test
  void acceptingAndAssertingAreIndependent() {
    var accepted = ControlClaims.empty().withAcceptance(new ControlClaims.Acceptance("owner", "idp=x", "taken on", "d", NOW));
    var asserted = ControlClaims.empty().withAssertion(assertion(1));

    assertThat(accepted.asserted()).isFalse();
    assertThat(asserted.accepted()).isFalse();
  }
}

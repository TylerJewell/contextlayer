package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityTest {

  private IdentityCluster add(IdentityCluster c, String rule, double confidence, String source, String id) {
    return c.apply(new IdentityEvent.MemberAdded("org", rule, "k", confidence, source, "Client", id));
  }

  @Test
  void emailMatchesIgnoreCaseAndWhitespace() {
    var a = IdentityRules.candidates(Map.of("email", "  Pat@Example.COM "));
    assertThat(a).extracting(IdentityRules.Candidate::key).containsExactly("pat@example.com");
    assertThat(a.get(0).confidence()).isEqualTo(1.0);
  }

  @Test
  void nameAndBirthDateKeyIgnoresAccentsPunctuationAndCase() {
    var one = IdentityRules.candidates(Map.of("name", "José  O'Neil", "dob", "1980-02-03"));
    var two = IdentityRules.candidates(Map.of("name", "jose oneil", "dob", "1980-02-03"));
    assertThat(one).extracting(IdentityRules.Candidate::key)
        .isEqualTo(two.stream().map(IdentityRules.Candidate::key).toList());
  }

  @Test
  void aRecordWithNoUsableAttributesProducesNoCandidates() {
    assertThat(IdentityRules.candidates(Map.of("name", "Pat"))).isEmpty();
  }

  @Test
  void aSingleRecordLinksNothing() {
    var c = add(IdentityCluster.empty(), "email", 1.0, "crm", "1");
    assertThat(c.status()).isEqualTo(IdentityCluster.Status.SINGLE);
    assertThat(c.usable()).isFalse();
  }

  @Test
  void highConfidenceMatchesAcrossSourcesAreTrustedAutomatically() {
    var c = add(add(IdentityCluster.empty(), "email", 1.0, "crm", "1"), "email", 1.0, "claims", "9");
    assertThat(c.status()).isEqualTo(IdentityCluster.Status.CONFIRMED);
    assertThat(c.usable()).isTrue();
  }

  @Test
  void lowConfidenceMatchesWaitForAPersonAndAreNotUsableUntilConfirmed() {
    var c = add(add(IdentityCluster.empty(), "name-dob", 0.7, "crm", "1"), "name-dob", 0.7, "claims", "9");
    assertThat(c.status()).isEqualTo(IdentityCluster.Status.PENDING_REVIEW);
    assertThat(c.usable()).isFalse();

    var confirmed = c.apply(new IdentityEvent.ReviewConfirmed("alice"));
    assertThat(confirmed.usable()).isTrue();

    var rejected = c.apply(new IdentityEvent.ReviewRejected("alice"));
    assertThat(rejected.status()).isEqualTo(IdentityCluster.Status.REJECTED);
    assertThat(rejected.usable()).isFalse();
  }

  @Test
  void unlinkingAMemberReturnsTheClusterToUnlinkedAndClearsEarlierReview() {
    var c = add(add(IdentityCluster.empty(), "name-dob", 0.7, "crm", "1"), "name-dob", 0.7, "claims", "9")
        .apply(new IdentityEvent.ReviewConfirmed("alice"))
        .apply(new IdentityEvent.MemberUnlinked("claims", "Client", "9", "alice", "wrong person"));
    assertThat(c.status()).isEqualTo(IdentityCluster.Status.SINGLE);
    assertThat(c.usable()).isFalse();
  }

  @Test
  void aNewMemberAfterAReviewNeedsANewReview() {
    var c = add(add(IdentityCluster.empty(), "name-dob", 0.7, "crm", "1"), "name-dob", 0.7, "claims", "9")
        .apply(new IdentityEvent.ReviewConfirmed("alice"));
    var withThird = add(c, "name-dob", 0.7, "billing", "5");
    assertThat(withThird.status()).isEqualTo(IdentityCluster.Status.PENDING_REVIEW);
  }
}

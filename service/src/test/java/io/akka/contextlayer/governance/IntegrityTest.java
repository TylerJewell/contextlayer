package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.governance.Integrity.Link;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegrityTest {

  private final Map<String, Evidence> records = new HashMap<>();
  private final List<Link> links = new ArrayList<>();

  private static Evidence evidence(int n) {
    return new Evidence("p:ev:c:" + n, "p", "custom-cl0003", "HumanDecisionRecorded", "WORKFLOW_STEP", EvaluationMode.ENFORCING,
        "APPROVE", "CONTINUED", "approved", "action-" + n, "bob", "idp=x;roles=approver", "digest", "he-" + n, false, 1_000L + n);
  }

  @BeforeEach
  void aChainOfFive() {
    String head = "";
    for (int n = 1; n <= 5; n++) {
      var e = evidence(n);
      records.put(e.evidenceId(), e);
      var link = Integrity.next(head, n - 1, e.evidenceId(), Integrity.recordDigest(e), 2_000L + n);
      links.add(link);
      head = link.chainDigest();
    }
  }

  private Integrity.Result check(long from, long limit) {
    return Integrity.check(from, limit, links.size(), seq -> seq >= 1 && seq <= links.size() ? Optional.of(links.get((int) seq - 1)) : Optional.empty(),
        id -> Optional.ofNullable(records.get(id)));
  }

  @Test
  void anUntouchedChainIsIntact() {
    var result = check(1, 100);

    assertThat(result.intact()).isTrue();
    assertThat(result.checked()).isEqualTo(5);
    assertThat(result.lastChainDigest()).isEqualTo(links.get(4).chainDigest());
  }

  @Test
  void equalContentHasAnEqualDigestAndAnyChangeHasAnother() {
    assertThat(Integrity.recordDigest(evidence(1))).isEqualTo(Integrity.recordDigest(evidence(1)));
    var changed = new Evidence("p:ev:c:1", "p", "custom-cl0003", "HumanDecisionRecorded", "WORKFLOW_STEP", EvaluationMode.ENFORCING,
        "DENY", "CONTINUED", "approved", "action-1", "bob", "idp=x;roles=approver", "digest", "he-1", false, 1_001L);
    assertThat(Integrity.recordDigest(changed)).isNotEqualTo(Integrity.recordDigest(evidence(1)));
  }

  @Test
  void aRecordAlteredAfterItWasChainedIsFound() {
    var e = evidence(3);
    records.put(e.evidenceId(), new Evidence(e.evidenceId(), "p", e.controlId(), e.eventType(), e.boundary(), e.mode(), "DENY",
        e.consequence(), e.reasonCode(), e.subject(), e.actor(), e.authority(), e.bundleDigest(), e.ref(), e.flagged(), e.at()));

    var result = check(1, 100);

    assertThat(result.intact()).isFalse();
    assertThat(result.problems()).singleElement().asString().contains("p:ev:c:3").contains("altered after it was chained");
  }

  @Test
  void aRecordThatIsMissingIsFound() {
    records.remove("p:ev:c:2");

    assertThat(check(1, 100).problems()).singleElement().asString().contains("p:ev:c:2").contains("is missing");
  }

  @Test
  void aLinkAlteredToMatchAlteredContentIsFoundBecauseTheNextLinkNoLongerFollows() {
    var forged = Integrity.next(links.get(0).chainDigest(), 1, "p:ev:c:2", "sha256:forged", 9L);
    links.set(1, forged);

    var problems = check(1, 100).problems();

    assertThat(problems).anyMatch(p -> p.contains("link 3 does not follow link 2"));
    assertThat(problems).anyMatch(p -> p.contains("record p:ev:c:2") && p.contains("altered after it was chained"));
  }

  @Test
  void aLinkWhoseDigestDoesNotMatchItsContentIsFound() {
    var l = links.get(3);
    links.set(3, new Link(l.seq(), l.recordId(), l.recordDigest(), l.previous(), "sha256:made-up", l.at()));

    assertThat(check(1, 100).problems()).anyMatch(p -> p.contains("link 4 was altered"));
  }

  @Test
  void aRemovedLinkEndsTheCheckAndIsNamed() {
    links.remove(2);

    var problems = check(1, 100).problems();

    assertThat(problems).isNotEmpty();
    assertThat(problems.get(problems.size() - 1)).contains("link");
  }

  @Test
  void aStretchInTheMiddleIsCheckedAgainstTheLinkBeforeIt() {
    var result = check(3, 2);

    assertThat(result.intact()).isTrue();
    assertThat(result.checked()).isEqualTo(2);
    assertThat(result.lastSeq()).isEqualTo(4);
  }

  @Test
  void aStretchIsLimitedAndEndsWhereTheChainEnds() {
    assertThat(check(1, 2).checked()).isEqualTo(2);
    assertThat(check(4, 100).checked()).isEqualTo(2);
  }
}

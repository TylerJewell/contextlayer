package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.governance.Compliance.Item;
import io.akka.contextlayer.governance.Compliance.Status;
import io.akka.contextlayer.governance.Compliance.Waiver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ComplianceTest {

  private static final long NOW = 10_000_000_000L;
  private static final Duration WEEK = Duration.ofDays(7);
  private static final long DAY = Duration.ofDays(1).toMillis();

  private static Item item(EvaluationMode mode, Verdict verdict, long daysAgo) {
    return new Item(mode, verdict, false, NOW - daysAgo * DAY);
  }

  private static Compliance.Standing assess(List<Item> items) {
    return Compliance.assess(items, null, WEEK, NOW);
  }

  @Test
  public void aControlNeverSeenEnforcingHasNoEvidence() {
    assertThat(assess(List.of()).status()).isEqualTo(Status.NO_EVIDENCE);
  }

  @Test
  public void shadowAdvisoryAndTestEvidenceProveNothing() {
    var standing = assess(List.of(
        item(EvaluationMode.SHADOW, Verdict.INCONCLUSIVE, 0), item(EvaluationMode.ADVISORY, Verdict.UPHELD, 0),
        item(EvaluationMode.TEST, Verdict.UPHELD, 0)));

    assertThat(standing.status()).isEqualTo(Status.NO_EVIDENCE);
    assertThat(standing.why()).contains("only shadow, advisory or test");
  }

  @Test
  public void recentUpheldEnforcementSatisfies() {
    var standing = assess(List.of(item(EvaluationMode.ENFORCING, Verdict.UPHELD, 1)));

    assertThat(standing.status()).isEqualTo(Status.SATISFIED);
    assertThat(standing.upheld()).isEqualTo(1);
  }

  @Test
  public void aBreachInTheWindowFailsEvenIfLaterPassesFollow() {
    var standing = assess(List.of(
        item(EvaluationMode.ENFORCING, Verdict.BREACHED, 3), item(EvaluationMode.ENFORCING, Verdict.UPHELD, 1)));

    assertThat(standing.status()).isEqualTo(Status.FAILING);
    assertThat(standing.breached()).isEqualTo(1);
  }

  @Test
  public void aBreachAgesOutOfTheWindow() {
    var standing = assess(List.of(
        item(EvaluationMode.ENFORCING, Verdict.BREACHED, 9), item(EvaluationMode.ENFORCING, Verdict.UPHELD, 1)));

    assertThat(standing.status()).isEqualTo(Status.SATISFIED);
  }

  @Test
  public void anErrorCountsAsAFailure() {
    assertThat(assess(List.of(item(EvaluationMode.ENFORCING, Verdict.ERROR, 0))).status()).isEqualTo(Status.FAILING);
  }

  @Test
  public void oldEnforcementIsStale() {
    var standing = assess(List.of(item(EvaluationMode.ENFORCING, Verdict.UPHELD, 30)));

    assertThat(standing.status()).isEqualTo(Status.STALE);
    assertThat(standing.lastEnforcingAt()).isEqualTo(NOW - 30 * DAY);
  }

  @Test
  public void enforcingEvidenceThatOnlyObservedIsUnresolved() {
    assertThat(assess(List.of(item(EvaluationMode.ENFORCING, Verdict.INCONCLUSIVE, 1))).status())
        .isEqualTo(Status.UNRESOLVED);
  }

  @Test
  public void aWaiverInForceOverridesEverythingAndALapsedOneDoesNot() {
    var failing = List.of(item(EvaluationMode.ENFORCING, Verdict.BREACHED, 0));

    assertThat(Compliance.assess(failing, new Waiver("accepted risk", "root", 0), WEEK, NOW).status())
        .isEqualTo(Status.WAIVED);
    assertThat(Compliance.assess(failing, new Waiver("accepted risk", "root", NOW + DAY), WEEK, NOW).status())
        .isEqualTo(Status.WAIVED);
    assertThat(Compliance.assess(failing, new Waiver("accepted risk", "root", NOW - DAY), WEEK, NOW).status())
        .isEqualTo(Status.FAILING);
  }

  @Test
  public void flaggedEvidenceIsCountedWithoutChangingTheStatus() {
    var standing = assess(List.of(
        new Item(EvaluationMode.ENFORCING, Verdict.UPHELD, true, NOW - DAY),
        new Item(EvaluationMode.ENFORCING, Verdict.UPHELD, false, NOW - DAY)));

    assertThat(standing.status()).isEqualTo(Status.SATISFIED);
    assertThat(standing.flagged()).isEqualTo(1);
    assertThat(standing.enforcing()).isEqualTo(2);
  }
}

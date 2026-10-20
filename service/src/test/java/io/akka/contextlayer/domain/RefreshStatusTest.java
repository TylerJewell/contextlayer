package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RefreshStatusTest {

  private static final long MINUTE = 60_000L;

  private RefreshStatus read(RefreshStatus.Outcome outcome, long at) {
    return RefreshStatus.of("org", "claims", "Claim", outcome, at, 3, 0, "hash", List.of());
  }

  @Test
  void aCompleteReadIsFreshUntilItsTimeToLiveRunsOut() {
    var status = read(RefreshStatus.Outcome.OK, 1_000);
    assertThat(status.freshWithin(15, 1_000 + 14 * MINUTE)).isTrue();
    assertThat(status.freshWithin(15, 1_000 + 15 * MINUTE)).isFalse();
  }

  @Test
  void aReadThatStoppedEarlyIsNeverFreshSoTheNextQuestionTriesAgain() {
    assertThat(read(RefreshStatus.Outcome.PARTIAL, 1_000).freshWithin(15, 1_001)).isFalse();
  }

  @Test
  void aFailedReadIsNeverFresh() {
    assertThat(read(RefreshStatus.Outcome.FAILED, 1_000).freshWithin(15, 1_001)).isFalse();
  }

  @Test
  void nothingEverReadIsNeverFresh() {
    assertThat(RefreshStatus.empty().freshWithin(15, 1_000)).isFalse();
  }
}

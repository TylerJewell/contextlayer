package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.application.QuestionService.Freshness;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.RefreshStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

public class QuestionServiceFreshnessTest {

  private static final long MINUTE = 60_000L;
  private static final long NOW = 100 * MINUTE;

  private static final QuestionService.Provider PROVIDER = new QuestionService.Provider("feed",
      new PackDefinition.EntityMapping("Claim", "", null, "claimId", List.of(), List.of(), null, null, null));

  private static RefreshStatus read(RefreshStatus.Outcome outcome, long minutesAgo) {
    return RefreshStatus.of("p", "feed", "Claim", outcome, NOW - minutesAgo * MINUTE, 1, 0, "h", List.of("a warning"));
  }

  @Test
  public void aScheduledSourceReadOnTimeIsMaintained() {
    var report = QuestionService.maintained(PROVIDER, "Claim", read(RefreshStatus.Outcome.OK, 9), NOW, 10);

    assertThat(report.freshness()).isEqualTo(Freshness.MAINTAINED);
  }

  @Test
  public void aScheduledSourceThatHasMissedTwoReadsIsStale() {
    var report = QuestionService.maintained(PROVIDER, "Claim", read(RefreshStatus.Outcome.OK, 21), NOW, 10);

    assertThat(report.freshness()).isEqualTo(Freshness.STALE);
    assertThat(report.warnings()).anyMatch(w -> w.contains("21 minutes ago") && w.contains("every 10"));
  }

  @Test
  public void aScheduledSourceWhoseLastReadFailedIsStaleEvenIfRecent() {
    var report = QuestionService.maintained(PROVIDER, "Claim", read(RefreshStatus.Outcome.FAILED, 1), NOW, 10);

    assertThat(report.freshness()).isEqualTo(Freshness.STALE);
  }

  @Test
  public void aPushedSourceIsCurrentWhileItKeepsSendingAndStaleWhenItGoesQuiet() {
    assertThat(QuestionService.streamed(PROVIDER, "Claim", read(RefreshStatus.Outcome.OK, 59), NOW, 60).freshness())
        .isEqualTo(Freshness.STREAMED);

    var quiet = QuestionService.streamed(PROVIDER, "Claim", read(RefreshStatus.Outcome.OK, 61), NOW, 60);

    assertThat(quiet.freshness()).isEqualTo(Freshness.STALE);
    assertThat(quiet.warnings()).anyMatch(w -> w.contains("61 minutes") && w.contains("every 60"));
  }

  @Test
  public void aPushedSourceThatHasSentNothingIsUnavailable() {
    var report = QuestionService.streamed(PROVIDER, "Claim", RefreshStatus.empty(), NOW, 60);

    assertThat(report.freshness()).isEqualTo(Freshness.UNAVAILABLE);
  }
}

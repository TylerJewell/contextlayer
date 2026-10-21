package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseFileTest {

  private CaseFile opened() {
    return CaseFile.empty().apply(new CaseEvent.Opened("org", "c1", "m1", 2, "CL-889", Map.of("daysOpen", 41),
        List.of("claims/Claim: REFRESHED_NOW"), "Claims Team B", "", 1000L));
  }

  @Test
  void openingRecordsTheEvidenceAndStartsTheTimeline() {
    var c = opened();
    assertThat(c.status()).isEqualTo(CaseFile.Status.OPEN);
    assertThat(c.evidence()).containsEntry("daysOpen", 41);
    assertThat(c.timeline()).hasSize(1);
    assertThat(c.timeline().get(0).text()).contains("CL-889").contains("Claims Team B");
  }

  @Test
  void everyChangeAddsATimelineEntryWithWhoDidIt() {
    var c = opened()
        .apply(new CaseEvent.Annotated("playbook claims-watch", "form not attached", 2000L))
        .apply(new CaseEvent.Resolved("priya", "attached the form", 3000L));
    assertThat(c.status()).isEqualTo(CaseFile.Status.RESOLVED);
    assertThat(c.timeline()).extracting(CaseFile.TimelineItem::actor)
        .containsExactly("watcher m1", "playbook claims-watch", "priya");
  }

  @Test
  void aClosedCaseCannotBeClosedOrAnnotatedAgain() {
    var closed = opened().apply(new CaseEvent.Dismissed("priya", "duplicate", 2000L));
    assertThat(closed.validateClose("bob")).isPresent();
    assertThat(closed.validateAnnotate("bob", "note")).isPresent();
  }

  @Test
  void closingNeedsAPerson() {
    assertThat(opened().validateClose("")).isPresent();
    assertThat(opened().validateClose("priya")).isEmpty();
    assertThat(opened().validateAnnotate("priya", " ")).isPresent();
  }

  @Test
  void aCaseWhoseRowStoppedMatchingStaysOpenForAPersonToClose() {
    var c = opened().apply(new CaseEvent.NoLongerMatching(2000L));
    assertThat(c.status()).isEqualTo(CaseFile.Status.NO_LONGER_MATCHING);
    assertThat(c.isClosed()).isFalse();
    assertThat(c.validateClose("priya")).isEmpty();
  }

  @Test
  void aMissingCaseCannotBeChanged() {
    assertThat(CaseFile.empty().validateClose("priya")).isPresent();
    assertThat(CaseFile.empty().validateNoLongerMatching()).isPresent();
  }
}

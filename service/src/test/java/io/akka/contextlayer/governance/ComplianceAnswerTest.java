package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.governance.ComplianceAnswer.Fact;
import io.akka.contextlayer.governance.ComplianceAnswer.Facts;
import io.akka.contextlayer.governance.ComplianceAnswer.Intent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComplianceAnswerTest {

  private static Facts facts() {
    return new Facts(Intent.CONTROL_STATUS, "custom-cl0003", 90, List.of(
        new Fact("Control custom-cl0003 is satisfied: 13 enforcing records, 13 upheld, 0 breached.", List.of("control:custom-cl0003")),
        new Fact("It was accepted by dana on 2026-10-16.", List.of("person:dana"))),
        List.of("Still needed: override procedure: none attached."));
  }

  @Test
  void aTextThatRestatesTheFactsIsSupported() {
    var text = "Control custom-cl0003 ran 13 times and none were breached. Dana accepted it. The override procedure is still needed.";

    assertThat(ComplianceAnswer.check(text, facts()).ok()).isTrue();
  }

  @Test
  void anInventedNumberIsFound() {
    var check = ComplianceAnswer.check("It ran 99 times with 13 upheld.", facts());

    assertThat(check.ok()).isFalse();
    assertThat(check.unsupported()).containsExactly("the number 99");
  }

  @Test
  void aControlTheFactsDoNotMentionIsFound() {
    var check = ComplianceAnswer.check("Control custom-cl0007 also applies.", facts());

    assertThat(check.unsupported()).containsExactly("the control custom-cl0007");
  }

  @Test
  void aVerdictNobodyRecordedIsFoundButOnlyIfTheFactsDoNotUseTheWord() {
    assertThat(ComplianceAnswer.check("The system is fully compliant.", facts()).unsupported())
        .contains("the claim \"compliant\"", "the claim \"fully compliant\"");
    var saysIt = new Facts(Intent.GAPS, "", 90, List.of(new Fact("No gaps were found.", List.of())), List.of());
    assertThat(ComplianceAnswer.check("There are no gaps.", saysIt).ok()).isTrue();
  }

  @Test
  void datesAndCommasInNumbersDoNotTripTheCheck() {
    var f = new Facts(Intent.INTEGRITY, "", 0, List.of(new Fact("The ledger holds 1,250 records, last changed 2026-10-19.", List.of())), List.of());

    assertThat(ComplianceAnswer.check("It holds 1,250 records as of 2026-10-19.", f).ok()).isTrue();
  }

  @Test
  void theQuestionsAreNamedAndOnlyTheOnesAboutAControlNeedOne() {
    assertThat(Intent.parse("control status")).contains(Intent.CONTROL_STATUS);
    assertThat(Intent.parse("  gaps ")).contains(Intent.GAPS);
    assertThat(Intent.parse("delete everything")).isEmpty();
    assertThat(Intent.parse(null)).isEmpty();
    assertThat(Intent.CONTROL_STATUS.needsControl()).isTrue();
    assertThat(Intent.GAPS.needsControl()).isFalse();
  }

  @Test
  void aPeriodIsKeptWithinBounds() {
    assertThat(ComplianceAnswer.period(null)).isEqualTo(90);
    assertThat(ComplianceAnswer.period(0)).isEqualTo(90);
    assertThat(ComplianceAnswer.period(30)).isEqualTo(30);
    assertThat(ComplianceAnswer.period(100_000)).isEqualTo(365);
  }

  @Test
  void thePlainFormGivesTheFactsThenWhatTheRecordsDoNotShow() {
    var plain = ComplianceAnswer.plain(facts());

    assertThat(plain).startsWith("Control custom-cl0003 is satisfied").contains("What the records do not show:")
        .contains("- Still needed: override procedure: none attached.");
  }
}

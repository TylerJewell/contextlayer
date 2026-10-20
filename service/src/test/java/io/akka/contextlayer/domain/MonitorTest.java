package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.domain.StructuredQuestion.Condition;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonitorTest {

  static MonitorDefinition definition() {
    var question = new StructuredQuestion("Claim", List.of(new Condition("status", Op.EQ, "OPEN")), List.of(),
        List.of("Claim.claimId", "Claim.daysOpen"), 100);
    return new MonitorDefinition("stalled claims", question, "Claim.claimId", 15, "", "Claims Team B");
  }

  private Monitor created() {
    return Monitor.empty().apply(new MonitorEvent.Created("org", "m1", definition(), "alice"));
  }

  private Monitor ran(Monitor m, List<String> keys, boolean complete, long at) {
    var plan = m.plan(keys, complete);
    return m.apply(new MonitorEvent.RunRecorded(plan.runNumber(), at, complete, plan.newCases(), plan.goneCases(), ""));
  }

  @Test
  void aValidDefinitionIsAccepted() {
    assertThat(definition().validate()).isEmpty();
  }

  @Test
  void theKeyMustBeOneOfTheReturnedFieldsAndTheIntervalMustBeSane() {
    var d = definition();
    assertThat(new MonitorDefinition(d.name(), d.question(), "Claim.status", 15, "", "").validate())
        .anyMatch(e -> e.contains("keyField"));
    assertThat(new MonitorDefinition(d.name(), d.question(), d.keyField(), 0, "", "").validate())
        .anyMatch(e -> e.contains("intervalMinutes"));
    assertThat(new MonitorDefinition(d.name(), d.question(), d.keyField(),
        MonitorDefinition.MAX_INTERVAL_MINUTES + 1, "", "").validate()).anyMatch(e -> e.contains("intervalMinutes"));
    assertThat(new MonitorDefinition("", d.question(), d.keyField(), 15, "", "").validate())
        .anyMatch(e -> e.contains("name"));
  }

  @Test
  void aBadQuestionInsideTheDefinitionIsReported() {
    var bad = new StructuredQuestion("claim", List.of(), List.of(), List.of("Claim.claimId"), 10);
    assertThat(new MonitorDefinition("m", bad, "Claim.claimId", 15, "", "").validate()).isNotEmpty();
  }

  @Test
  void theFirstRunOpensACaseForEveryMatchingRow() {
    var plan = created().plan(List.of("1", "2"), true);
    assertThat(plan.runNumber()).isEqualTo(1);
    assertThat(plan.newCases()).containsOnlyKeys("1", "2");
    assertThat(plan.goneCases()).isEmpty();
  }

  @Test
  void aRowThatKeepsMatchingDoesNotOpenAnotherCase() {
    var m = ran(created(), List.of("1", "2"), true, 100);
    var plan = m.plan(List.of("1", "2", "3"), true);
    assertThat(plan.newCases()).containsOnlyKeys("3");
    assertThat(plan.goneCases()).isEmpty();
  }

  @Test
  void aRowThatStopsMatchingClosesItsOwnCase() {
    var first = ran(created(), List.of("1", "2"), true, 100);
    var caseOfTwo = first.known().get("2");
    var plan = first.plan(List.of("1"), true);
    assertThat(plan.goneCases()).containsEntry("2", caseOfTwo).hasSize(1);
  }

  @Test
  void anIncompleteRunNeverClosesACaseButMayStillOpenNewOnes() {
    var first = ran(created(), List.of("1", "2"), true, 100);
    var plan = first.plan(List.of("3"), false);
    assertThat(plan.goneCases()).isEmpty();
    assertThat(plan.newCases()).containsOnlyKeys("3");
    var after = ran(first, List.of("3"), false, 200);
    assertThat(after.known()).containsKeys("1", "2", "3");
  }

  @Test
  void aRowThatReturnsAfterLeavingGetsAFreshCase() {
    var first = ran(created(), List.of("1"), true, 100);
    var firstCase = first.known().get("1");
    var gone = ran(first, List.of(), true, 200);
    assertThat(gone.known()).isEmpty();
    var back = gone.plan(List.of("1"), true);
    assertThat(back.newCases().get("1")).isNotEqualTo(firstCase);
  }

  @Test
  void caseIdsAreSafeEntityIdsEvenWhenTheKeyHasSeparatorsOrIsLong() {
    var id = created().caseIdFor("weird:key|with/chars " + "x".repeat(500), 1);
    assertThat(id).startsWith("org:m1:").doesNotContain("|").hasSizeLessThan(100);
    assertThat(ScopedId.orgOf(id)).isEqualTo("org");
  }

  @Test
  void theSameKeyInTheSameRunAlwaysGetsTheSameCaseId() {
    assertThat(created().caseIdFor("1", 3)).isEqualTo(created().caseIdFor("1", 3));
    assertThat(created().caseIdFor("1", 3)).isNotEqualTo(created().caseIdFor("1", 4));
  }

  @Test
  void duplicateKeysInOneRunOpenOneCase() {
    assertThat(created().plan(List.of("1", "1"), true).newCases()).hasSize(1);
  }

  @Test
  void anUpdateRaisesTheVersionAndKeepsWhatWasSeen() {
    var m = ran(created(), List.of("1"), true, 100)
        .apply(new MonitorEvent.DefinitionUpdated(definition(), "alice"));
    assertThat(m.version()).isEqualTo(2);
    assertThat(m.known()).containsKey("1");
    assertThat(Map.copyOf(m.known())).hasSize(1);
  }
}

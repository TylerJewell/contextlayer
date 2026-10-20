package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.contextlayer.domain.Monitor;
import io.akka.contextlayer.domain.MonitorDefinition;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitorEntityTest {

  private MonitorDefinition definition() {
    var question = new StructuredQuestion("Claim", List.of(), List.of(), List.of("Claim.claimId"), 100);
    return new MonitorDefinition("stalled", question, "Claim.claimId", 15, "", "");
  }

  private EventSourcedTestKit<Monitor, ?, MonitorEntity> created() {
    var testKit = EventSourcedTestKit.of(MonitorEntity::new);
    testKit.method(MonitorEntity::create).invoke(new MonitorEntity.Create("org", "m1", definition(), "alice"));
    return testKit;
  }

  @Test
  void recordingTheSameRunTwiceReturnsTheFirstPlanAndChangesNothing() {
    var testKit = created();
    var first = testKit.method(MonitorEntity::recordRun)
        .invoke(new MonitorEntity.RecordRun(5000L, List.of("1", "2"), true, "complete"));
    var again = testKit.method(MonitorEntity::recordRun)
        .invoke(new MonitorEntity.RecordRun(5000L, List.of("9"), true, "different input, same run"));

    assertThat(again.getReply()).isEqualTo(first.getReply());
    assertThat(testKit.getState().runCount()).isEqualTo(1);
    assertThat(testKit.getState().known()).containsOnlyKeys("1", "2");
  }

  @Test
  void aRunOnAMissingMonitorIsRefused() {
    var testKit = EventSourcedTestKit.of(MonitorEntity::new);
    var result = testKit.method(MonitorEntity::recordRun)
        .invoke(new MonitorEntity.RecordRun(1L, List.of("1"), true, ""));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void aSecondCreateIsRefused() {
    var testKit = created();
    assertThat(testKit.method(MonitorEntity::create)
        .invoke(new MonitorEntity.Create("org", "m1", definition(), "alice")).isError()).isTrue();
  }

  @Test
  void anInvalidDefinitionIsRefusedWithEveryProblem() {
    var testKit = EventSourcedTestKit.of(MonitorEntity::new);
    var bad = new MonitorDefinition("", null, "x", 0, "", "");
    var result = testKit.method(MonitorEntity::create).invoke(new MonitorEntity.Create("org", "m1", bad, "alice"));
    assertThat(result.isError()).isTrue();
    assertThat(result.getError()).contains("name").contains("question").contains("intervalMinutes");
  }

  @Test
  void disablingAndEnablingAreRecordedOnce() {
    var testKit = created();
    testKit.method(MonitorEntity::setEnabled).invoke(new MonitorEntity.SetEnabled(false, "alice"));
    testKit.method(MonitorEntity::setEnabled).invoke(new MonitorEntity.SetEnabled(false, "alice"));
    assertThat(testKit.getState().enabled()).isFalse();
    assertThat(testKit.getAllEvents()).hasSize(2);
  }
}

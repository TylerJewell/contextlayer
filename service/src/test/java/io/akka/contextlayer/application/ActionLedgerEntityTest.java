package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.contextlayer.domain.ActionEvent;
import io.akka.contextlayer.domain.ActionRecord;
import io.akka.contextlayer.domain.ActionStatus;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.domain.RiskClass;
import org.junit.jupiter.api.Test;

class ActionLedgerEntityTest {

  private ActionLedgerEntity.Request request(RiskClass risk, AssistantStage stage) {
    return new ActionLedgerEntity.Request("org", "a1", "k1", "record.update", "{}", risk, stage, "rel1", true, false);
  }

  @Test
  void repeatingARequestWithTheSameKeyRecordsNothingNew() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.NEEDS_APPROVAL, AssistantStage.LIVE));
    var second = testKit.method(ActionLedgerEntity::request)
        .invoke(request(RiskClass.NEEDS_APPROVAL, AssistantStage.LIVE));

    assertThat(second.isReply()).isTrue();
    assertThat(testKit.getAllEvents()).hasSize(1);
  }

  @Test
  void shadowModeRecordsWouldDoAndCannotBeStarted() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.UNATTENDED, AssistantStage.SHADOW));

    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.WOULD_DO);
    assertThat(testKit.method(ActionLedgerEntity::start).invoke().isError()).isTrue();
  }

  @Test
  void anActionThatNeedsApprovalCannotStartUntilApproved() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.NEEDS_APPROVAL, AssistantStage.LIVE));

    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    assertThat(testKit.method(ActionLedgerEntity::start).invoke().isError()).isTrue();

    testKit.method(ActionLedgerEntity::approve).invoke(new ActionLedgerEntity.Approve("alice"));
    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.APPROVED);
    assertThat(testKit.getState().decidedBy()).isEqualTo("alice");

    testKit.method(ActionLedgerEntity::start).invoke();
    testKit.method(ActionLedgerEntity::succeed).invoke(new ActionLedgerEntity.Finish("done"));
    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.SUCCEEDED);
  }

  @Test
  void aRejectedActionCannotBeApprovedLater() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.NEEDS_APPROVAL, AssistantStage.LIVE));
    testKit.method(ActionLedgerEntity::reject).invoke(new ActionLedgerEntity.Reject("alice", "not now"));

    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.REJECTED);
    assertThat(testKit.method(ActionLedgerEntity::approve)
        .invoke(new ActionLedgerEntity.Approve("bob")).isError()).isTrue();
  }

  @Test
  void anUnknownOutcomeIsResolvedOnlyByAPersonAndOnlyOnce() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.UNATTENDED, AssistantStage.LIVE));
    testKit.method(ActionLedgerEntity::start).invoke();
    testKit.method(ActionLedgerEntity::markOutcomeUnknown).invoke(new ActionLedgerEntity.Finish("timeout"));
    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.OUTCOME_UNKNOWN);

    assertThat(testKit.method(ActionLedgerEntity::succeed)
        .invoke(new ActionLedgerEntity.Finish("retry")).isError()).isTrue();

    testKit.method(ActionLedgerEntity::resolveUnknown)
        .invoke(new ActionLedgerEntity.ResolveUnknown("alice", false, "target system shows no change"));
    assertThat(testKit.getState().status()).isEqualTo(ActionStatus.FAILED);
    assertThat(testKit.method(ActionLedgerEntity::resolveUnknown)
        .invoke(new ActionLedgerEntity.ResolveUnknown("alice", true, "again")).isError()).isTrue();
  }

  @Test
  void everyChangeIsRecordedAsAnEventForTheAuditTrail() {
    var testKit = EventSourcedTestKit.of(ActionLedgerEntity::new);
    testKit.method(ActionLedgerEntity::request).invoke(request(RiskClass.UNATTENDED, AssistantStage.LIVE));
    testKit.method(ActionLedgerEntity::start).invoke();
    testKit.method(ActionLedgerEntity::succeed).invoke(new ActionLedgerEntity.Finish("ok"));

    assertThat(testKit.getAllEvents()).hasSize(3);
    assertThat(testKit.getAllEvents().get(0)).isInstanceOf(ActionEvent.Requested.class);
    assertThat((ActionRecord) testKit.getState()).extracting(ActionRecord::status).isEqualTo(ActionStatus.SUCCEEDED);
  }
}

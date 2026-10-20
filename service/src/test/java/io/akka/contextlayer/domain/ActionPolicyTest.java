package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActionPolicyTest {

  @Test
  void anActionThatIsNeverAvailableIsDeniedInEveryStage() {
    for (var stage : AssistantStage.values()) {
      assertThat(ActionPolicy.decide(stage, true, RiskClass.NEVER_BY_ASSISTANT).status())
          .isEqualTo(ActionStatus.DENIED);
    }
  }

  @Test
  void aDraftAssistantIsDenied() {
    assertThat(ActionPolicy.decide(AssistantStage.DRAFT, false, RiskClass.UNATTENDED).status())
        .isEqualTo(ActionStatus.DENIED);
  }

  @Test
  void shadowRecordsWhatWouldHaveBeenDoneForEveryAllowedRisk() {
    assertThat(ActionPolicy.decide(AssistantStage.SHADOW, true, RiskClass.UNATTENDED).status())
        .isEqualTo(ActionStatus.WOULD_DO);
    assertThat(ActionPolicy.decide(AssistantStage.SHADOW, true, RiskClass.NEEDS_APPROVAL).status())
        .isEqualTo(ActionStatus.WOULD_DO);
  }

  @Test
  void liveRunsUnattendedActionsAndHoldsRiskyOnesForAPerson() {
    assertThat(ActionPolicy.decide(AssistantStage.LIVE, true, RiskClass.UNATTENDED).status())
        .isEqualTo(ActionStatus.APPROVED);
    assertThat(ActionPolicy.decide(AssistantStage.LIVE, true, RiskClass.NEEDS_APPROVAL).status())
        .isEqualTo(ActionStatus.AWAITING_APPROVAL);
  }

  @Test
  void liveWithoutAnApprovedReleaseIsDenied() {
    assertThat(ActionPolicy.decide(AssistantStage.LIVE, false, RiskClass.UNATTENDED).status())
        .isEqualTo(ActionStatus.DENIED);
  }
}

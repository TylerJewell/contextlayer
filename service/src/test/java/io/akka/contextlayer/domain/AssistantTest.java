package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssistantTest {

  private Assistant created() {
    return Assistant.empty().apply(new AssistantEvent.Created("org", "a1", "keep claims moving", "alice"));
  }

  private Assistant withRelease(Assistant a, String definition) {
    return a.apply(new AssistantEvent.ReleaseAdded(Assistant.releaseIdFor(definition), definition, "alice"));
  }

  @Test
  void releaseIdIsStableForTheSameDefinitionAndDiffersWhenItChanges() {
    assertThat(Assistant.releaseIdFor("v1")).isEqualTo(Assistant.releaseIdFor("v1"));
    assertThat(Assistant.releaseIdFor("v1")).isNotEqualTo(Assistant.releaseIdFor("v2"));
  }

  @Test
  void liveRequiresAnApprovedRelease() {
    var a = withRelease(created(), "v1");
    assertThat(a.validateStage(AssistantStage.LIVE, "alice")).isPresent();
    var approved = a.apply(new AssistantEvent.ReleaseApproved(Assistant.releaseIdFor("v1"), "alice"));
    assertThat(approved.validateStage(AssistantStage.LIVE, "alice")).isEmpty();
  }

  @Test
  void shadowRequiresAtLeastOneRelease() {
    assertThat(created().validateStage(AssistantStage.SHADOW, "alice")).isPresent();
    assertThat(withRelease(created(), "v1").validateStage(AssistantStage.SHADOW, "alice")).isEmpty();
  }

  @Test
  void onlyTheAccountableOwnerApprovesOrChangesStage() {
    var a = withRelease(created(), "v1");
    var id = Assistant.releaseIdFor("v1");
    assertThat(a.validateApprove(id, "mallory")).isPresent();
    assertThat(a.validateApprove(id, "alice")).isEmpty();
    assertThat(a.validateStage(AssistantStage.SHADOW, "mallory")).isPresent();
  }

  @Test
  void aLiveAssistantKeepsRunningItsApprovedReleaseWhenANewUnapprovedOneIsAdded() {
    var v1 = Assistant.releaseIdFor("v1");
    var live = withRelease(created(), "v1")
        .apply(new AssistantEvent.ReleaseApproved(v1, "alice"))
        .apply(new AssistantEvent.StageChanged(AssistantStage.LIVE, "alice"));
    var withNewer = withRelease(live, "v2");

    assertThat(withNewer.runnableRelease()).get().extracting(Assistant.Release::releaseId).isEqualTo(v1);
  }

  @Test
  void shadowTriesTheNewestReleaseEvenBeforeApproval() {
    var v2 = Assistant.releaseIdFor("v2");
    var shadow = withRelease(withRelease(created(), "v1"), "v2")
        .apply(new AssistantEvent.StageChanged(AssistantStage.SHADOW, "alice"));

    assertThat(shadow.runnableRelease()).get().extracting(Assistant.Release::releaseId).isEqualTo(v2);
  }

  @Test
  void aDraftAssistantRunsNothing() {
    assertThat(withRelease(created(), "v1").runnableRelease()).isEmpty();
  }

  @Test
  void addingTheSameDefinitionTwiceKeepsOneRelease() {
    var a = withRelease(withRelease(created(), "v1"), "v1");
    assertThat(a.releases()).hasSize(1);
  }
}

package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.contextlayer.domain.IdentityCluster;
import org.junit.jupiter.api.Test;

class IdentityEntityTest {

  private EventSourcedTestKit<IdentityCluster, ?, IdentityEntity> withTwoMembers() {
    var testKit = EventSourcedTestKit.of(IdentityEntity::new);
    testKit.method(IdentityEntity::addMember)
        .invoke(new IdentityEntity.AddMember("org", "email", "a@x", 1.0, "crm", "Client", "c1"));
    testKit.method(IdentityEntity::addMember)
        .invoke(new IdentityEntity.AddMember("org", "email", "a@x", 1.0, "claims", "Claim", "CL-1"));
    return testKit;
  }

  @Test
  void unlinkingAMemberRemovesItAndRecordsWhoAndWhy() {
    var testKit = withTwoMembers();

    var result = testKit.method(IdentityEntity::unlink)
        .invoke(new IdentityEntity.Unlink("claims", "Claim", "CL-1", "system", "the record is no longer in the source"));

    assertThat(result.isReply()).isTrue();
    assertThat(testKit.getState().members()).hasSize(1);
    assertThat(testKit.getState().status()).isEqualTo(IdentityCluster.Status.SINGLE);
    assertThat(testKit.getAllEvents()).hasSize(3);
  }

  @Test
  void unlinkingARecordThatIsNotAMemberChangesNothingAndIsNotAnError() {
    var testKit = withTwoMembers();

    var result = testKit.method(IdentityEntity::unlink)
        .invoke(new IdentityEntity.Unlink("claims", "Claim", "CL-404", "system", "gone"));

    assertThat(result.isReply()).isTrue();
    assertThat(testKit.getState().members()).hasSize(2);
    assertThat(testKit.getAllEvents()).hasSize(2);
  }

  @Test
  void unlinkingFromAClusterThatNeverExistedChangesNothingAndIsNotAnError() {
    var testKit = EventSourcedTestKit.of(IdentityEntity::new);

    var result = testKit.method(IdentityEntity::unlink)
        .invoke(new IdentityEntity.Unlink("claims", "Claim", "CL-1", "system", "gone"));

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply().exists()).isFalse();
    assertThat(testKit.getAllEvents()).isEmpty();
  }

  @Test
  void unlinkingTwiceIsTheSameAsUnlinkingOnce() {
    var testKit = withTwoMembers();
    var command = new IdentityEntity.Unlink("claims", "Claim", "CL-1", "system", "gone");

    testKit.method(IdentityEntity::unlink).invoke(command);
    testKit.method(IdentityEntity::unlink).invoke(command);

    assertThat(testKit.getAllEvents()).hasSize(3);
  }

  @Test
  void anUnlinkMustSayWhoIsDoingIt() {
    var testKit = withTwoMembers();

    var result = testKit.method(IdentityEntity::unlink)
        .invoke(new IdentityEntity.Unlink("claims", "Claim", "CL-1", "", "gone"));

    assertThat(result.isError()).isTrue();
    assertThat(testKit.getState().members()).hasSize(2);
  }
}

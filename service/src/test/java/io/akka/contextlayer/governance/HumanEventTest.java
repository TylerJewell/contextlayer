package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HumanEventTest {

  private static final long T0 = 1_000_000_000L;
  private static final long HOUR = Duration.ofHours(1).toMillis();

  private static Reviewer reviewer(String subject, String... roles) {
    return new Reviewer(subject, List.of(roles), "test-idp", false, "idp=test-idp;roles=" + String.join(",", roles));
  }

  private static Reviewer rehearsing(String subject, String... roles) {
    return new Reviewer(subject, List.of(roles), "test-idp", true, "idp=test-idp");
  }

  private static HumanPolicy policy(int required, int minimumSeconds, boolean enforce, List<String> independence) {
    return new HumanPolicy("human-system-authorization", "action", "HITL", true, List.of("approver"),
        required == 1 ? "SINGLE" : "TWO_PERSON", required, independence, "approver", "PT24H", "RESUME_WITH_DEFAULT",
        "DENY", List.of("approver", "admin"), List.of("APPROVE", "DENY", "ESCALATE", "REQUEST_MORE_INFO"), true,
        minimumSeconds, enforce);
  }

  private static HumanEvent raised(HumanPolicy policy, String proposer) {
    var raised = new HumanEventEvent.Raised("acme:he:a1:k1", "acme", Controls.ACTION_APPROVAL, "sha256:x", policy,
        "action", "send a letter", "a1", Map.of("actionKey", "k1"), "workflow", "risk=MEDIUM", "needs a person",
        "Should the letter go out?", List.of("The address is right."), proposer, T0, T0 + 24 * HOUR);
    return HumanEvent.empty().apply(raised);
  }

  private static HumanEvent standard() {
    return raised(policy(1, 10, false, List.of("proposer")), "alice");
  }

  private static HumanEvent applied(HumanEvent event, Checked<HumanEventEvent> step) {
    assertThat(step.refused()).as(String.valueOf(step.refusal())).isFalse();
    return event.apply(step.value());
  }

  private static HumanEvent claimed(HumanEvent event, Reviewer who, long at) {
    return applied(event, event.claim(who, at));
  }

  @Test
  public void aRaisedEventIsOpenAndRoutedToTheControlsRoles() {
    var event = standard();

    assertThat(event.state()).isEqualTo(HumanState.OPEN);
    assertThat(event.pending()).isTrue();
    assertThat(event.history()).hasSize(1);
    assertThat(event.slaState(T0)).isEqualTo("running");
  }

  @Test
  public void aDecisionNeedsAClaimWhenReviewTimeIsMeasured() {
    var event = standard();

    var step = event.decide(reviewer("bob", "approver"), "APPROVE", "looks right", List.of(), 0, T0 + 1000);

    assertThat(step.refusal().kind()).isEqualTo(Refusal.Kind.CONFLICT);
    assertThat(step.refusal().message()).contains("claim the event first");
  }

  @Test
  public void aDecisionIsFlaggedWhenSubmittedBeforeTheMinimumReviewTime() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0 + 1000);

    var done = applied(event, event.decide(bob, "APPROVE", "looks right", List.of(), 0, T0 + 4000));

    assertThat(done.state()).isEqualTo(HumanState.DECIDED);
    assertThat(done.outcomeAction()).isEqualTo("APPROVE");
    var decision = done.decisions().get(0);
    assertThat(decision.reviewSeconds()).isEqualTo(3);
    assertThat(decision.belowMinimum()).isTrue();
    assertThat(decision.authority()).contains("approver");
  }

  @Test
  public void aDecisionAfterTheMinimumReviewTimeIsNotFlagged() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0 + 1000);

    var done = applied(event, event.decide(bob, "DENY", "wrong address", List.of(), 0, T0 + 61_000));

    assertThat(done.decisions().get(0).belowMinimum()).isFalse();
    assertThat(done.decisions().get(0).reviewSeconds()).isEqualTo(60);
  }

  @Test
  public void aControlThatEnforcesTheMinimumRefusesARushedDecision() {
    var strict = raised(policy(1, 10, true, List.of()), "alice");
    var bob = reviewer("bob", "approver");
    var event = claimed(strict, bob, T0 + 1000);

    var step = event.decide(bob, "APPROVE", "ok", List.of(), 0, T0 + 3000);

    assertThat(step.refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(step.refusal().message()).contains("minimum review time");
  }

  @Test
  public void aControlThatMeasuresNoReviewTimeNeedsNoClaim() {
    var event = raised(policy(1, 0, false, List.of()), "alice");

    var done = applied(event, event.decide(reviewer("bob", "approver"), "APPROVE", "ok", List.of(), 0, T0 + 1000));

    assertThat(done.state()).isEqualTo(HumanState.DECIDED);
  }

  @Test
  public void aRationaleIsRequiredWhereTheControlSaysSo() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0);

    var step = event.decide(bob, "APPROVE", "  ", List.of(), 0, T0 + 20_000);

    assertThat(step.refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(step.refusal().message()).contains("rationale");
  }

  @Test
  public void thePersonWhoProposedItCannotDecideIt() {
    var alice = reviewer("alice", "approver");
    var event = claimed(standard(), alice, T0);

    var step = event.decide(alice, "APPROVE", "mine", List.of(), 0, T0 + 20_000);

    assertThat(step.refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
  }

  @Test
  public void aSystemProposerDoesNotBlockAnyone() {
    var event = raised(policy(1, 0, false, List.of("proposer")), "system:monitor");

    assertThat(event.decide(reviewer("bob", "approver"), "APPROVE", "ok", List.of(), 0, T0 + 1).refused()).isFalse();
  }

  @Test
  public void aRoleOutsideTheControlCannotActAndAnAdminCan() {
    var event = standard();

    assertThat(event.claim(reviewer("eve", "reviewer"), T0).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    assertThat(event.claim(reviewer("eve", "operator"), T0).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    assertThat(event.claim(reviewer("root", "admin"), T0).refused()).isFalse();
  }

  @Test
  public void anActionTheControlDoesNotAllowIsInvalid() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0);

    assertThat(event.decide(bob, "HALT", "x", List.of(), 0, T0 + 20_000).refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.decide(bob, "ESCALATE", "x", List.of(), 0, T0 + 20_000).refusal().message()).contains("own route");
  }

  @Test
  public void onlyTheHolderMayDecideAClaimedEvent() {
    var event = claimed(standard(), reviewer("bob", "approver"), T0);
    var carol = reviewer("carol", "approver");

    assertThat(event.claim(carol, T0 + 1000).refusal().kind()).isEqualTo(Refusal.Kind.CONFLICT);
    assertThat(event.decide(carol, "APPROVE", "ok", List.of(), 0, T0 + 20_000).refusal().message())
        .contains("claimed by another");
  }

  @Test
  public void anUntouchedClaimLapsesAndAnotherReviewerCanTakeOver() {
    var event = claimed(standard(), reviewer("bob", "approver"), T0);
    long later = T0 + HumanEvent.CLAIM_MILLIS + 1;

    assertThat(event.effectiveState(later)).isEqualTo(HumanState.OPEN);
    var taken = claimed(event, reviewer("carol", "approver"), later);
    assertThat(taken.holder()).isEqualTo("carol");
  }

  @Test
  public void aHolderCanReleaseAndOthersCannot() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0);

    assertThat(event.release(reviewer("carol", "approver"), T0 + 1).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    var released = applied(event, event.release(bob, T0 + 1));
    assertThat(released.state()).isEqualTo(HumanState.OPEN);
    assertThat(released.holder()).isEmpty();
  }

  @Test
  public void aCheckThatIsNotTheEventsIsRefused() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0);

    assertThat(event.decide(bob, "APPROVE", "ok", List.of("something else"), 0, T0 + 20_000).refusal().kind())
        .isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.decide(bob, "APPROVE", "ok", List.of("The address is right."), 0, T0 + 20_000).refused()).isFalse();
  }

  @Test
  public void twoPersonApprovalNeedsTwoDifferentPeopleAndADenialSettlesAtOnce() {
    var bob = reviewer("bob", "approver");
    var carol = reviewer("carol", "approver");
    var event = claimed(raised(policy(2, 10, false, List.of()), "alice"), bob, T0);

    var first = applied(event, event.decide(bob, "APPROVE", "ok", List.of(), 0, T0 + 20_000));
    assertThat(first.state()).isEqualTo(HumanState.AWAITING_SECOND);
    assertThat(first.pending()).isTrue();
    assertThat(first.decide(bob, "APPROVE", "again", List.of(), 0, T0 + 30_000).refusal().message())
        .contains("already voted");

    var second = applied(first, first.decide(carol, "APPROVE", "agreed", List.of(), T0 + 21_000, T0 + 40_000));
    assertThat(second.state()).isEqualTo(HumanState.DECIDED);
    assertThat(second.outcomeAction()).isEqualTo("APPROVE");
    assertThat(second.decisions().get(1).reviewSeconds()).isEqualTo(19);

    var denied = applied(first, first.decide(carol, "DENY", "no", List.of(), 0, T0 + 40_000));
    assertThat(denied.state()).isEqualTo(HumanState.DECIDED);
    assertThat(denied.outcomeAction()).isEqualTo("DENY");
  }

  @Test
  public void escalationMovesTheEventToTheNextRoleAndStopsAtTheLastLevel() {
    var bob = reviewer("bob", "approver");
    var root = reviewer("root", "admin");
    var event = standard();

    var escalated = applied(event, event.escalate(bob, "needs the admin", T0 + 1));

    assertThat(escalated.state()).isEqualTo(HumanState.ESCALATED);
    assertThat(escalated.currentRoles()).containsExactly("admin");
    assertThat(escalated.claim(bob, T0 + 2).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    assertThat(escalated.escalate(root, "higher", T0 + 3).refusal().message()).contains("last level");
    assertThat(escalated.escalate(bob, " ", T0 + 3).refused()).isTrue();
    var rootClaimed = claimed(escalated, root, T0 + 4);
    assertThat(applied(rootClaimed, rootClaimed.decide(root, "APPROVE", "ok", List.of(), 0, T0 + 30_000)).state())
        .isEqualTo(HumanState.DECIDED);
  }

  @Test
  public void sendBackGoesToTheProposerWhoAloneCanResubmit() {
    var bob = reviewer("bob", "approver");
    var alice = reviewer("alice", "operator");
    var event = claimed(standard(), bob, T0);

    var sent = applied(event, event.sendBack(bob, "which letter?", T0 + 1000));

    assertThat(sent.state()).isEqualTo(HumanState.CHANGES_REQUESTED);
    assertThat(sent.holder()).isEmpty();
    assertThat(sent.decide(bob, "APPROVE", "ok", List.of(), 0, T0 + 20_000).refusal().kind()).isEqualTo(Refusal.Kind.CONFLICT);
    assertThat(sent.resubmit(bob, T0 + 2000).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    assertThat(applied(sent, sent.resubmit(alice, T0 + 2000)).state()).isEqualTo(HumanState.OPEN);
  }

  @Test
  public void anEventProposedBySystemHasNoOneToSendBackTo() {
    var event = raised(policy(1, 10, false, List.of()), "system:monitor");

    assertThat(event.sendBack(reviewer("bob", "approver"), "why?", T0).refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
  }

  @Test
  public void theProposerCanWithdrawAndNobodyElseExceptAnAdmin() {
    var event = standard();

    assertThat(event.withdraw(reviewer("bob", "approver"), "no", T0).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    var withdrawn = applied(event, event.withdraw(reviewer("alice", "operator"), "changed my mind", T0 + 1));
    assertThat(withdrawn.state()).isEqualTo(HumanState.WITHDRAWN);
    assertThat(withdrawn.pending()).isFalse();
  }

  @Test
  public void anEventPastItsDueTimeTimesOutWithTheControlsDefault() {
    var event = standard();

    assertThat(event.expire(T0 + HOUR).refusal().message()).contains("not due yet");
    var done = applied(event, event.expire(T0 + 25 * HOUR));

    assertThat(done.state()).isEqualTo(HumanState.TIMED_OUT);
    assertThat(done.outcomeAction()).isEqualTo("DENY");
    assertThat(done.slaOutcome()).isEqualTo("TIMED_OUT");
    assertThat(done.slaState(T0 + 26 * HOUR)).isEqualTo("missed");
  }

  @Test
  public void anOverdueEventCanNoLongerBeActedOn() {
    var event = standard();
    long late = T0 + 25 * HOUR;

    assertThat(event.slaState(late)).isEqualTo("overdue");
    assertThat(event.claim(reviewer("bob", "approver"), late).refusal().message()).contains("past its due time");
  }

  @Test
  public void slaGoesAtRiskInTheLastFifthOfTheTime() {
    assertThat(standard().slaState(T0 + 19 * HOUR)).isEqualTo("running");
    assertThat(standard().slaState(T0 + 20 * HOUR)).isEqualTo("at-risk");
  }

  @Test
  public void aRehearsalIsRecordedAndChangesNothingReal() {
    var event = standard();
    var console = rehearsing("bob", "approver");

    assertThat(event.claim(console, T0).refused()).isFalse();
    assertThat(event.claim(console, T0).value()).isNull();
    var after = applied(event, event.decide(console, "APPROVE", "just trying", List.of(), 0, T0 + 20_000));

    assertThat(after.state()).isEqualTo(HumanState.OPEN);
    assertThat(after.decisions()).isEmpty();
    assertThat(after.rehearsals()).hasSize(1);
    assertThat(after.outcomeAction()).isEmpty();
  }

  @Test
  public void aRehearsalStillObeysTheRules() {
    var event = standard();

    assertThat(event.decide(rehearsing("bob", "approver"), "APPROVE", "", List.of(), 0, T0 + 20_000).refusal().kind())
        .isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.decide(rehearsing("eve", "viewer"), "APPROVE", "x", List.of(), 0, T0 + 20_000).refusal().kind())
        .isEqualTo(Refusal.Kind.FORBIDDEN);
  }

  @Test
  public void applyingADecisionIsRecordedOnce() {
    var bob = reviewer("bob", "approver");
    var event = claimed(standard(), bob, T0);
    var decided = applied(event, event.decide(bob, "APPROVE", "ok", List.of(), 0, T0 + 20_000));

    assertThat(decided.markApplied("ran", T0 + 21_000).refused()).isFalse();
    var done = applied(decided, decided.markApplied("ran", T0 + 21_000));

    assertThat(done.state()).isEqualTo(HumanState.APPLIED);
    assertThat(done.applied()).isEqualTo("ran");
    assertThat(done.markApplied("again", T0 + 22_000).refusal().kind()).isEqualTo(Refusal.Kind.CONFLICT);
  }

  @Test
  public void anEventThatDoesNotExistIsNotFound() {
    var none = HumanEvent.empty();

    assertThat(none.claim(reviewer("bob", "approver"), T0).refusal().kind()).isEqualTo(Refusal.Kind.NOT_FOUND);
    assertThat(none.expire(T0).refusal().kind()).isEqualTo(Refusal.Kind.NOT_FOUND);
  }

  @Test
  public void aDeadlineCanBeExtendedOnceWithAReasonAndWithinAWeek() {
    var bob = reviewer("bob", "approver");
    var event = standard();

    var extended = applied(event, event.extend(bob, 4 * HOUR, "the owner is travelling", T0 + 1000));

    assertThat(extended.dueAt()).isEqualTo(event.dueAt() + 4 * HOUR);
    assertThat(extended.extended()).isTrue();
    assertThat(extended.expire(event.dueAt() + 1).refusal().message()).contains("not due yet");
    assertThat(extended.extend(bob, HOUR, "again", T0 + 2000).refusal().kind()).isEqualTo(Refusal.Kind.CONFLICT);
    assertThat(event.extend(bob, HOUR, " ", T0).refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.extend(bob, 8 * 24 * HOUR, "too long", T0).refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.extend(bob, 0, "nothing", T0).refusal().kind()).isEqualTo(Refusal.Kind.INVALID);
    assertThat(event.extend(reviewer("eve", "reviewer"), HOUR, "x", T0).refusal().kind()).isEqualTo(Refusal.Kind.FORBIDDEN);
    assertThat(event.extend(rehearsing("bob", "approver"), HOUR, "just looking", T0).value()).isNull();
    assertThat(extended.history().get(extended.history().size() - 1).note()).contains("deadline extended").contains("travelling");
  }

  @Test
  public void aDeadlineThatHasPassedCannotBeExtended() {
    var event = standard();

    assertThat(event.extend(reviewer("bob", "approver"), HOUR, "late", T0 + 25 * HOUR).refusal().message())
        .contains("past its due time");
  }
}

package io.akka.contextlayer.governance;

import akka.javasdk.annotations.TypeName;
import java.util.List;
import java.util.Map;

public sealed interface HumanEventEvent {

  /**
   * An event raised for a person to judge.
   *
   * @param policy the control's human policy as it stood when raised, so a later catalog change does
   *     not alter an event already in front of someone
   * @param catalogDigest the catalog version in force
   * @param detail what the decision applies to: for an action, assistantId, actionKey, actionType, risk
   */
  @TypeName("human-raised")
  record Raised(
      String id,
      String orgId,
      String controlId,
      String catalogDigest,
      HumanPolicy policy,
      String subjectKind,
      String summary,
      String assistantId,
      Map<String, String> detail,
      String detector,
      String rule,
      String reason,
      String question,
      List<String> checks,
      String proposedBy,
      long at,
      long dueAt)
      implements HumanEventEvent {}

  @TypeName("human-claimed")
  record Claimed(Reviewer reviewer, long at, long expiresAt) implements HumanEventEvent {}

  @TypeName("human-released")
  record Released(Reviewer reviewer, long at) implements HumanEventEvent {}

  /** A vote. {@code state} is DECIDED when it settles the event, AWAITING_SECOND when more votes are needed. */
  @TypeName("human-decided")
  record Decided(HumanDecision decision, HumanState state, String outcomeAction) implements HumanEventEvent {}

  /** A rehearsal: recorded for the audit trail, with no effect on the event. */
  @TypeName("human-rehearsed")
  record Rehearsed(HumanDecision decision) implements HumanEventEvent {}

  @TypeName("human-escalated")
  record Escalated(Reviewer reviewer, String reason, int level, long at) implements HumanEventEvent {}

  @TypeName("human-sent-back")
  record SentBack(Reviewer reviewer, String requested, long at) implements HumanEventEvent {}

  @TypeName("human-resubmitted")
  record Resubmitted(Reviewer reviewer, long at) implements HumanEventEvent {}

  /** More time to decide. An event can have one, so the deadline cannot be pushed back indefinitely. */
  @TypeName("human-extended")
  record Extended(Reviewer reviewer, long extraMillis, String reason, long newDueAt, long at) implements HumanEventEvent {}

  @TypeName("human-withdrawn")
  record Withdrawn(Reviewer reviewer, String reason, long at) implements HumanEventEvent {}

  /** The time ran out. {@code outcomeAction} is the default the control names, or empty when it names none. */
  @TypeName("human-timed-out")
  record TimedOut(String outcomeAction, String slaOutcome, long at) implements HumanEventEvent {}

  @TypeName("human-applied")
  record Applied(String note, long at) implements HumanEventEvent {}

  @TypeName("human-apply-failed")
  record ApplyFailed(String error, long at) implements HumanEventEvent {}
}

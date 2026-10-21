package io.akka.contextlayer.governance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One situation a human control says a person must judge, from being raised until it closes.
 *
 * <p>Every rule about who may do what lives here and answers with a {@link Refusal} rather than an
 * exception, so a caller can tell "not yours to decide" (403) from "already decided" (409) from
 * "the decision is not valid" (422). Nothing here reads a clock: the time is passed in.
 *
 * <p>A rehearsal (a reviewer marked {@code rehearsal}) goes through the same checks, but where the
 * act would change the event it answers {@code Checked.ok(null)}: nothing to record. A rehearsed
 * decision is the one exception; it is recorded on the side and changes no state.
 *
 * <p>The control's policy is copied into the event when it is raised. A claim lapses on its own
 * after {@link #CLAIM_MILLIS}, and an event past its due time can no longer be decided; both are
 * worked out from the time passed in, so no timer is needed for them.
 */
public record HumanEvent(
    boolean exists,
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
    long raisedAt,
    long dueAt,
    HumanState state,
    int level,
    String holder,
    List<String> holderRoles,
    long claimedAt,
    long claimExpiresAt,
    List<HumanDecision> decisions,
    List<HumanDecision> rehearsals,
    String outcomeAction,
    String slaOutcome,
    String applied,
    String applyError,
    long closedAt,
    List<Step> history) {

  /** How long an untouched claim holds an event. */
  public static final long CLAIM_MILLIS = Duration.ofMinutes(30).toMillis();

  private static final int MAX_TEXT = 4000;
  private static final Set<String> USE_OTHER_ROUTE = Set.of("ESCALATE", "REQUEST_MORE_INFO", "SEND_BACK");

  public record Step(long at, String state, String actor, String note) {}

  public HumanEvent {
    detail = detail == null ? Map.of() : Map.copyOf(detail);
    checks = checks == null ? List.of() : List.copyOf(checks);
    holderRoles = holderRoles == null ? List.of() : List.copyOf(holderRoles);
    decisions = decisions == null ? List.of() : List.copyOf(decisions);
    rehearsals = rehearsals == null ? List.of() : List.copyOf(rehearsals);
    history = history == null ? List.of() : List.copyOf(history);
  }

  public static HumanEvent empty() {
    return new HumanEvent(false, "", "", "", "", null, "", "", "", Map.of(), "", "", "", "", List.of(), "", 0, 0,
        HumanState.OPEN, 0, "", List.of(), 0, 0, List.of(), List.of(), "", "", "", "", 0, List.of());
  }

  // ---- questions about the event

  public boolean pending() {
    return state.pending();
  }

  /** The state a caller should treat the event as being in at {@code now}: a lapsed claim is open again. */
  public HumanState effectiveState(long now) {
    if (state == HumanState.CLAIMED && now > claimExpiresAt) return level > 0 ? HumanState.ESCALATED : HumanState.OPEN;
    return state;
  }

  public boolean overdue(long now) {
    return pending() && now > dueAt;
  }

  /** The roles that may act at the current escalation level. */
  public List<String> currentRoles() {
    if (level == 0) return policy.roles();
    var chain = policy.escalationChain();
    return level < chain.size() ? List.of(chain.get(level)) : List.of();
  }

  /** running, at-risk, overdue, met or missed. */
  public String slaState(long now) {
    if (!pending()) return closedAt > 0 && closedAt <= dueAt ? "met" : "missed";
    if (now > dueAt) return "overdue";
    long total = Math.max(1, dueAt - raisedAt);
    return (dueAt - now) * 5 < total ? "at-risk" : "running";
  }

  /**
   * The detail key listing, comma-separated, the people who supplied what the event is about, such as the records of
   * an identity review. With {@code contributors} in the control's independence, none of them may decide it.
   */
  public static final String CONTRIBUTORS = "_contributors";

  public List<String> contributors() {
    var listed = detail.getOrDefault(CONTRIBUTORS, "");
    return listed.isBlank() ? List.of() : List.of(listed.split(","));
  }

  public boolean proposedBySystem() {
    return proposedBy.isBlank() || proposedBy.startsWith("system:");
  }

  // ---- commands: each answers the event to record, or why not

  public Checked<HumanEventEvent> claim(Reviewer reviewer, long now) {
    var gate = openFor(reviewer, now);
    if (gate != null) return Checked.refuse(gate);
    // Someone who may not decide may not hold the event either, or the people who may would be kept from it.
    var barred = independence(reviewer);
    if (barred != null) return Checked.refuse(barred);
    var current = effectiveState(now);
    if (current == HumanState.CHANGES_REQUESTED) {
      return Checked.refuse(Refusal.conflict("the event is with its proposer for changes"));
    }
    if (current == HumanState.CLAIMED && !holder.equals(reviewer.subject())) {
      return Checked.refuse(Refusal.conflict("claimed by another reviewer"));
    }
    if (current == HumanState.AWAITING_SECOND && votedBy(reviewer.subject())) {
      return Checked.refuse(Refusal.conflict("you have already voted on this event"));
    }
    if (reviewer.rehearsal()) return Checked.ok(null);
    return Checked.ok(new HumanEventEvent.Claimed(reviewer, now, now + CLAIM_MILLIS));
  }

  public Checked<HumanEventEvent> release(Reviewer reviewer, long now) {
    if (!exists) return Checked.refuse(notFound());
    if (reviewer.rehearsal()) return Checked.ok(null);
    if (effectiveState(now) != HumanState.CLAIMED) return Checked.refuse(Refusal.conflict("the event is not claimed"));
    if (!holder.equals(reviewer.subject()) && !reviewer.holdsAnyOf(List.of("admin"))) {
      return Checked.refuse(Refusal.forbidden("claimed by another reviewer"));
    }
    return Checked.ok(new HumanEventEvent.Released(reviewer, now));
  }

  /**
   * A decision. It needs a claim, except a later vote of an event needing several, when the voter
   * says when they were shown it ({@code presentedAt}, or 0 for now).
   */
  public Checked<HumanEventEvent> decide(
      Reviewer reviewer, String action, String rationale, List<String> ticked, long presentedAt, long now) {
    var gate = openFor(reviewer, now);
    if (gate != null) return Checked.refuse(gate);
    if (action == null || action.isBlank()) return Checked.refuse(Refusal.invalid("an action is required"));
    if (USE_OTHER_ROUTE.contains(action)) return Checked.refuse(Refusal.invalid(action + " has its own route"));
    if (!policy.allowedActions().contains(action)) {
      return Checked.refuse(Refusal.invalid("this control does not allow " + action));
    }
    var current = effectiveState(now);
    if (current == HumanState.CHANGES_REQUESTED) {
      return Checked.refuse(Refusal.conflict("the event is with its proposer for changes"));
    }
    if (votedBy(reviewer.subject())) return Checked.refuse(Refusal.conflict("you have already voted on this event"));
    var barred = independence(reviewer);
    if (barred != null) return Checked.refuse(barred);
    // A decision needs a claim, because the claim is when the person is shown the event and the
    // minimum review time counts from it. A control that measures no review time needs no claim, and
    // a rehearsal never takes the event.
    long shownAt;
    if (reviewer.rehearsal()) {
      shownAt = now;
    } else if (current == HumanState.AWAITING_SECOND) {
      shownAt = presentedAt > 0 && presentedAt <= now ? presentedAt : now;
    } else if (current == HumanState.CLAIMED) {
      if (!holder.equals(reviewer.subject())) return Checked.refuse(Refusal.conflict("claimed by another reviewer"));
      shownAt = claimedAt;
    } else if (policy.minimumReviewSeconds() == 0) {
      shownAt = now;
    } else {
      return Checked.refuse(Refusal.conflict("claim the event first"));
    }
    if (policy.rationaleRequired() && (rationale == null || rationale.isBlank())) {
      return Checked.refuse(Refusal.invalid("a rationale is required"));
    }
    if (rationale != null && rationale.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the rationale is too long"));
    var checksTicked = ticked == null ? List.<String>of() : ticked;
    for (var check : checksTicked) {
      if (!checks.contains(check)) return Checked.refuse(Refusal.invalid("not a check of this event: " + check));
    }
    long seconds = Math.max(0, (now - shownAt) / 1000);
    boolean below = seconds < policy.minimumReviewSeconds();
    if (below && policy.enforceMinimum()) {
      return Checked.refuse(Refusal.invalid("the minimum review time is " + policy.minimumReviewSeconds() + " seconds"));
    }
    var decision = new HumanDecision(reviewer, action, rationale == null ? "" : rationale.trim(), true,
        checksTicked, shownAt, now, seconds, below, reviewer.authority());
    if (reviewer.rehearsal()) return Checked.ok(new HumanEventEvent.Rehearsed(decision));

    int votes = decisions.size() + 1;
    boolean settles = !"APPROVE".equals(action) || votes >= policy.required();
    return Checked.ok(new HumanEventEvent.Decided(decision,
        settles ? HumanState.DECIDED : HumanState.AWAITING_SECOND, settles ? action : ""));
  }

  public Checked<HumanEventEvent> escalate(Reviewer reviewer, String why, long now) {
    var gate = openFor(reviewer, now);
    if (gate != null) return Checked.refuse(gate);
    if (why == null || why.isBlank()) return Checked.refuse(Refusal.invalid("a reason is required"));
    if (why.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the reason is too long"));
    if (level + 1 >= policy.escalationChain().size()) {
      return Checked.refuse(Refusal.invalid("the event is already with the last level"));
    }
    if (reviewer.rehearsal()) return Checked.ok(null);
    return Checked.ok(new HumanEventEvent.Escalated(reviewer, why.trim(), level + 1, now));
  }

  public Checked<HumanEventEvent> sendBack(Reviewer reviewer, String requested, long now) {
    var gate = openFor(reviewer, now);
    if (gate != null) return Checked.refuse(gate);
    if (requested == null || requested.isBlank()) return Checked.refuse(Refusal.invalid("say what changes are requested"));
    if (requested.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the request is too long"));
    if (proposedBySystem()) return Checked.refuse(Refusal.invalid("there is no proposer to send this back to"));
    if (reviewer.rehearsal()) return Checked.ok(null);
    return Checked.ok(new HumanEventEvent.SentBack(reviewer, requested.trim(), now));
  }

  /** Only the proposer takes an event back from CHANGES_REQUESTED. */
  public Checked<HumanEventEvent> resubmit(Reviewer reviewer, long now) {
    if (!exists) return Checked.refuse(notFound());
    if (state != HumanState.CHANGES_REQUESTED) return Checked.refuse(Refusal.conflict("the event is not waiting for changes"));
    if (!reviewer.subject().equals(proposedBy)) return Checked.refuse(Refusal.forbidden("only the proposer can resubmit"));
    return Checked.ok(new HumanEventEvent.Resubmitted(reviewer, now));
  }

  /** The longest a deadline can be extended by. */
  public static final long MAX_EXTENSION_MILLIS = Duration.ofDays(7).toMillis();

  /** Whether the deadline has been moved already; at most once. */
  public boolean extended() {
    return history.stream().anyMatch(step -> step.note().startsWith(EXTENDED));
  }

  private static final String EXTENDED = "deadline extended";

  public Checked<HumanEventEvent> extend(Reviewer reviewer, long extraMillis, String why, long now) {
    var gate = openFor(reviewer, now);
    if (gate != null) return Checked.refuse(gate);
    if (why == null || why.isBlank()) return Checked.refuse(Refusal.invalid("a reason is required"));
    if (why.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the reason is too long"));
    if (extraMillis <= 0 || extraMillis > MAX_EXTENSION_MILLIS) {
      return Checked.refuse(Refusal.invalid("the extension must be more than nothing and at most 7 days"));
    }
    if (extended()) return Checked.refuse(Refusal.conflict("the deadline has already been extended once"));
    if (reviewer.rehearsal()) return Checked.ok(null);
    return Checked.ok(new HumanEventEvent.Extended(reviewer, extraMillis, why.trim(), dueAt + extraMillis, now));
  }

  /** The question this event asks no longer exists (the records it concerned changed); closes it without a person. */
  public Checked<HumanEventEvent> supersede(String why, long now) {
    if (!exists) return Checked.refuse(notFound());
    if (!pending()) return Checked.refuse(Refusal.conflict("the event is " + state + ", not pending"));
    return Checked.ok(new HumanEventEvent.Withdrawn(Reviewer.system("superseded"), why, now));
  }

  public Checked<HumanEventEvent> withdraw(Reviewer reviewer, String why, long now) {
    if (!exists) return Checked.refuse(notFound());
    if (!pending()) return Checked.refuse(Refusal.conflict("the event is " + state + ", not pending"));
    if (!reviewer.subject().equals(proposedBy) && !reviewer.holdsAnyOf(List.of("admin"))) {
      return Checked.refuse(Refusal.forbidden("only the proposer can withdraw"));
    }
    if (reviewer.rehearsal()) return Checked.ok(null);
    return Checked.ok(new HumanEventEvent.Withdrawn(reviewer, why == null ? "" : why.trim(), now));
  }

  /** Called when the time is up. Does nothing to an event that is not pending or not yet due. */
  public Checked<HumanEventEvent> expire(long now) {
    if (!exists) return Checked.refuse(notFound());
    if (!pending()) return Checked.refuse(Refusal.conflict("the event is " + state + ", not pending"));
    if (now <= dueAt) return Checked.refuse(Refusal.conflict("the event is not due yet"));
    boolean withDefault = "RESUME_WITH_DEFAULT".equals(policy.timeoutBehaviour()) && policy.timeoutDefault() != null;
    boolean abandon = "ABANDON".equals(policy.timeoutBehaviour());
    return Checked.ok(new HumanEventEvent.TimedOut(withDefault ? policy.timeoutDefault() : "",
        abandon ? "ABANDONED" : "TIMED_OUT", now));
  }

  public Checked<HumanEventEvent> markApplied(String note, long now) {
    if (!exists) return Checked.refuse(notFound());
    if (state != HumanState.DECIDED && state != HumanState.TIMED_OUT) {
      return Checked.refuse(Refusal.conflict("the event is " + state + ", nothing to apply"));
    }
    return Checked.ok(new HumanEventEvent.Applied(note == null ? "" : note, now));
  }

  public Checked<HumanEventEvent> markApplyFailed(String error, long now) {
    if (!exists) return Checked.refuse(notFound());
    return Checked.ok(new HumanEventEvent.ApplyFailed(error == null ? "" : error, now));
  }

  // ---- applying events

  public HumanEvent apply(HumanEventEvent event) {
    return switch (event) {
      case HumanEventEvent.Raised e -> new HumanEvent(true, e.id(), e.orgId(), e.controlId(), e.catalogDigest(),
          e.policy(), e.subjectKind(), e.summary(), e.assistantId(), e.detail(), e.detector(), e.rule(), e.reason(),
          e.question(), e.checks(), e.proposedBy(), e.at(), e.dueAt(), HumanState.OPEN, 0, "", List.of(), 0, 0,
          List.of(), List.of(), "", "", "", "", 0,
          List.of(new Step(e.at(), "OPEN", "system", "routed to " + String.join(", ", e.policy().roles()))));
      case HumanEventEvent.Claimed e -> next(HumanState.CLAIMED, e.reviewer().subject(), "claimed", e.at())
          .holding(e.reviewer().subject(), e.reviewer().roles(), e.at(), e.expiresAt());
      case HumanEventEvent.Released e -> next(level > 0 ? HumanState.ESCALATED : HumanState.OPEN, e.reviewer().subject(),
          "released", e.at()).holding("", List.of(), 0, 0);
      case HumanEventEvent.Decided e -> decided(e);
      case HumanEventEvent.Rehearsed e -> withRehearsal(e.decision());
      case HumanEventEvent.Escalated e -> new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy,
          subjectKind, summary, assistantId, detail, detector, rule, reason, question, checks, proposedBy, raisedAt,
          dueAt, HumanState.ESCALATED, e.level(), "", List.of(), 0, 0, decisions, rehearsals, outcomeAction,
          slaOutcome, applied, applyError, closedAt,
          appended(e.at(), "ESCALATED", e.reviewer().subject(), "escalated to " + policy.escalationChain().get(e.level())
              + ": " + e.reason()));
      case HumanEventEvent.SentBack e -> next(HumanState.CHANGES_REQUESTED, e.reviewer().subject(),
          "sent back: " + e.requested(), e.at()).holding("", List.of(), 0, 0);
      case HumanEventEvent.Resubmitted e -> next(level > 0 ? HumanState.ESCALATED : HumanState.OPEN,
          e.reviewer().subject(), "resubmitted", e.at());
      case HumanEventEvent.Extended e -> new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind,
          summary, assistantId, detail, detector, rule, reason, question, checks, proposedBy, raisedAt, e.newDueAt(),
          state, level, holder, holderRoles, claimedAt, claimExpiresAt, decisions, rehearsals, outcomeAction, slaOutcome,
          applied, applyError, closedAt, appended(e.at(), state.name(), e.reviewer().subject(),
              EXTENDED + " by " + Duration.ofMillis(e.extraMillis()) + ": " + e.reason()));
      case HumanEventEvent.Withdrawn e -> closing(HumanState.WITHDRAWN, e.reviewer().subject(),
          "withdrawn" + (e.reason().isBlank() ? "" : ": " + e.reason()), e.at(), "", "");
      case HumanEventEvent.TimedOut e -> closing(HumanState.TIMED_OUT, "system",
          "timed out" + (e.outcomeAction().isBlank() ? "" : ", default " + e.outcomeAction()), e.at(),
          e.outcomeAction(), e.slaOutcome());
      case HumanEventEvent.Applied e -> withState(state == HumanState.TIMED_OUT ? HumanState.TIMED_OUT : HumanState.APPLIED,
          "system", "applied: " + e.note(), e.at(), e.note(), "");
      case HumanEventEvent.ApplyFailed e -> withState(state, "system", "apply failed: " + e.error(), e.at(), applied,
          e.error());
    };
  }

  // ---- internals

  private HumanEvent decided(HumanEventEvent.Decided e) {
    var votes = new ArrayList<>(decisions);
    votes.add(e.decision());
    var note = e.decision().action() + " by " + e.decision().reviewer().subject()
        + (e.decision().belowMinimum() ? " (below the minimum review time)" : "");
    boolean settled = e.state() == HumanState.DECIDED;
    return new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind, summary, assistantId, detail,
        detector, rule, reason, question, checks, proposedBy, raisedAt, dueAt, e.state(), level,
        settled ? holder : "", settled ? holderRoles : List.of(), claimedAt, 0, votes, rehearsals,
        settled ? e.outcomeAction() : outcomeAction, settled ? "COMPLETED" : slaOutcome, applied, applyError,
        settled ? e.decision().submittedAt() : closedAt,
        appended(e.decision().submittedAt(), e.state().name(), e.decision().reviewer().subject(), note));
  }

  private HumanEvent withRehearsal(HumanDecision decision) {
    var all = new ArrayList<>(rehearsals);
    all.add(decision);
    return new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind, summary, assistantId, detail,
        detector, rule, reason, question, checks, proposedBy, raisedAt, dueAt, state, level, holder, holderRoles,
        claimedAt, claimExpiresAt, decisions, all, outcomeAction, slaOutcome, applied, applyError, closedAt,
        appended(decision.submittedAt(), state.name(), decision.reviewer().subject(),
            "rehearsal of " + decision.action() + ", no effect"));
  }

  private HumanEvent next(HumanState to, String actor, String note, long at) {
    return withState(to, actor, note, at, applied, applyError);
  }

  private HumanEvent withState(HumanState to, String actor, String note, long at, String nextApplied, String nextError) {
    return new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind, summary, assistantId, detail,
        detector, rule, reason, question, checks, proposedBy, raisedAt, dueAt, to, level, holder, holderRoles,
        claimedAt, claimExpiresAt, decisions, rehearsals, outcomeAction, slaOutcome, nextApplied, nextError, closedAt,
        appended(at, to.name(), actor, note));
  }

  private HumanEvent closing(HumanState to, String actor, String note, long at, String action, String sla) {
    return new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind, summary, assistantId, detail,
        detector, rule, reason, question, checks, proposedBy, raisedAt, dueAt, to, level, "", List.of(), claimedAt, 0,
        decisions, rehearsals, action, sla, applied, applyError, at, appended(at, to.name(), actor, note));
  }

  private HumanEvent holding(String by, List<String> roles, long at, long expires) {
    return new HumanEvent(exists, id, orgId, controlId, catalogDigest, policy, subjectKind, summary, assistantId, detail,
        detector, rule, reason, question, checks, proposedBy, raisedAt, dueAt, state, level, by, roles,
        at == 0 ? claimedAt : at, expires, decisions, rehearsals, outcomeAction, slaOutcome, applied, applyError,
        closedAt, history);
  }

  private List<Step> appended(long at, String stateName, String actor, String note) {
    var all = new ArrayList<>(history);
    all.add(new Step(at, stateName, actor, note));
    return all;
  }

  /** Common checks for acting on a pending event: it exists, is pending and due, and the reviewer's roles fit. */
  private Refusal openFor(Reviewer reviewer, long now) {
    if (!exists) return notFound();
    if (!pending()) return Refusal.conflict("the event is " + state + ", not pending");
    if (now > dueAt) return Refusal.conflict("the event is past its due time");
    if (!reviewer.holdsAnyOf(currentRoles())) {
      return Refusal.forbidden("requires one of the roles " + currentRoles());
    }
    return null;
  }

  /** Why the control's independence rules bar this person from deciding, or null when they do not. */
  private Refusal independence(Reviewer reviewer) {
    if (policy.independence().contains("proposer") && !proposedBySystem() && reviewer.subject().equals(proposedBy)) {
      return Refusal.forbidden("the person who proposed this cannot decide it");
    }
    if (policy.independence().contains("contributors") && contributors().contains(reviewer.subject())) {
      return Refusal.forbidden("a person who supplied what is under review cannot decide it");
    }
    return null;
  }

  private boolean votedBy(String subject) {
    return decisions.stream().anyMatch(d -> d.reviewer().subject().equals(subject));
  }

  private static Refusal notFound() {
    return new Refusal(Refusal.Kind.NOT_FOUND, "event not found");
  }
}

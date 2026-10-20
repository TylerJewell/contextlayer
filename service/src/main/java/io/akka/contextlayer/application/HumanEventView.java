package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.governance.HumanEventEvent;
import io.akka.contextlayer.governance.HumanState;
import java.util.List;

/** The queue of human events: one row per event, enough to list, filter and order them. */
@Component(id = "human-event-view")
public class HumanEventView extends View {

  public record Row(
      String id,
      String orgId,
      String controlId,
      String state,
      boolean pending,
      String mode,
      String subjectKind,
      String summary,
      String assistantId,
      String proposedBy,
      String holder,
      int level,
      long raisedAt,
      long dueAt,
      String outcomeAction,
      long closedAt) {

    Row with(HumanState nextState, String nextHolder, int nextLevel, String nextOutcome, long nextClosedAt) {
      return new Row(id, orgId, controlId, nextState.name(), nextState.pending(), mode, subjectKind, summary,
          assistantId, proposedBy, nextHolder, nextLevel, raisedAt, dueAt, nextOutcome, nextClosedAt);
    }

    HumanState stateValue() {
      return HumanState.valueOf(state);
    }

    HumanState openState() {
      return level > 0 ? HumanState.ESCALATED : HumanState.OPEN;
    }
  }

  public record Rows(List<Row> items) {}

  public record ByOrganization(String orgId, int max) {}

  public record ByPending(String orgId, boolean pending, int max) {}

  @Consume.FromEventSourcedEntity(HumanEventEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onEvent(HumanEventEvent event) {
      return switch (event) {
        case HumanEventEvent.Raised e -> effects().updateRow(new Row(e.id(), e.orgId(), e.controlId(),
            HumanState.OPEN.name(), true, e.policy().mode(), e.subjectKind(), e.summary(), e.assistantId(),
            e.proposedBy(), "", 0, e.at(), e.dueAt(), "", 0));
        case HumanEventEvent.Claimed e -> effects().updateRow(
            rowState().with(HumanState.CLAIMED, e.reviewer().subject(), rowState().level(), "", 0));
        case HumanEventEvent.Released e ->
            effects().updateRow(rowState().with(rowState().openState(), "", rowState().level(), "", 0));
        case HumanEventEvent.Decided e -> effects().updateRow(rowState().with(e.state(),
            e.state() == HumanState.DECIDED ? e.decision().reviewer().subject() : "", rowState().level(),
            e.outcomeAction(), e.state() == HumanState.DECIDED ? e.decision().submittedAt() : 0));
        case HumanEventEvent.Rehearsed e -> effects().ignore();
        case HumanEventEvent.Extended e -> {
          var r = rowState();
          yield effects().updateRow(new Row(r.id(), r.orgId(), r.controlId(), r.state(), r.pending(), r.mode(),
              r.subjectKind(), r.summary(), r.assistantId(), r.proposedBy(), r.holder(), r.level(), r.raisedAt(),
              e.newDueAt(), r.outcomeAction(), r.closedAt()));
        }
        case HumanEventEvent.Escalated e ->
            effects().updateRow(rowState().with(HumanState.ESCALATED, "", e.level(), "", 0));
        case HumanEventEvent.SentBack e ->
            effects().updateRow(rowState().with(HumanState.CHANGES_REQUESTED, "", rowState().level(), "", 0));
        case HumanEventEvent.Resubmitted e ->
            effects().updateRow(rowState().with(rowState().openState(), "", rowState().level(), "", 0));
        case HumanEventEvent.Withdrawn e ->
            effects().updateRow(rowState().with(HumanState.WITHDRAWN, "", rowState().level(), "", e.at()));
        case HumanEventEvent.TimedOut e ->
            effects().updateRow(rowState().with(HumanState.TIMED_OUT, "", rowState().level(), e.outcomeAction(), e.at()));
        case HumanEventEvent.Applied e -> effects().updateRow(rowState().stateValue() == HumanState.TIMED_OUT
            ? rowState()
            : rowState().with(HumanState.APPLIED, rowState().holder(), rowState().level(), rowState().outcomeAction(),
                rowState().closedAt()));
        case HumanEventEvent.ApplyFailed e -> effects().ignore();
      };
    }
  }

  @Query("""
      SELECT * AS items FROM human_events
      WHERE orgId = :orgId
      ORDER BY raisedAt DESC
      LIMIT :max
      """)
  public QueryEffect<Rows> byOrganization(ByOrganization query) {
    return queryResult();
  }

  @Query("""
      SELECT * AS items FROM human_events
      WHERE orgId = :orgId AND pending = :pending
      ORDER BY raisedAt DESC
      LIMIT :max
      """)
  public QueryEffect<Rows> byPending(ByPending query) {
    return queryResult();
  }
}

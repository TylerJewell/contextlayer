package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.ActionEvent;
import java.util.List;

/** Audit and work-queue reads over the action ledger: one row per action. */
@Component(id = "action-ledger-view")
public class ActionLedgerView extends View {

  public record ActionRow(
      String orgId,
      String assistantId,
      String actionKey,
      String actionType,
      String status,
      String reason,
      String decidedBy) {

    ActionRow with(String nextStatus, String nextReason, String nextDecidedBy) {
      return new ActionRow(orgId, assistantId, actionKey, actionType, nextStatus, nextReason, nextDecidedBy);
    }
  }

  public record ActionRows(List<ActionRow> items) {}

  public record ByAssistant(String orgId, String assistantId) {}

  public record ByStatus(String orgId, String status) {}

  @Consume.FromEventSourcedEntity(ActionLedgerEntity.class)
  public static class Updater extends TableUpdater<ActionRow> {
    public Effect<ActionRow> onEvent(ActionEvent event) {
      return switch (event) {
        case ActionEvent.Requested e -> effects().updateRow(new ActionRow(
            e.orgId(), e.assistantId(), e.actionKey(), e.actionType(), e.status().name(), e.reason(), ""));
        case ActionEvent.Approved e ->
            effects().updateRow(rowState().with("APPROVED", "approved by a person", e.approvedBy()));
        case ActionEvent.Rejected e ->
            effects().updateRow(rowState().with("REJECTED", e.reason(), e.rejectedBy()));
        case ActionEvent.Started e ->
            effects().updateRow(rowState().with("IN_FLIGHT", rowState().reason(), rowState().decidedBy()));
        case ActionEvent.Succeeded e ->
            effects().updateRow(rowState().with("SUCCEEDED", e.detail(), rowState().decidedBy()));
        case ActionEvent.Failed e ->
            effects().updateRow(rowState().with("FAILED", e.detail(), rowState().decidedBy()));
        case ActionEvent.OutcomeUnknown e ->
            effects().updateRow(rowState().with("OUTCOME_UNKNOWN", e.detail(), rowState().decidedBy()));
        case ActionEvent.UnknownResolved e -> effects().updateRow(rowState().with(
            e.succeeded() ? "SUCCEEDED" : "FAILED", e.note(), e.resolvedBy()));
      };
    }
  }

  @Query("""
      SELECT * AS items FROM actions
      WHERE orgId = :orgId AND assistantId = :assistantId
      """)
  public QueryEffect<ActionRows> byAssistant(ByAssistant query) {
    return queryResult();
  }

  @Query("""
      SELECT * AS items FROM actions
      WHERE orgId = :orgId AND status = :status
      """)
  public QueryEffect<ActionRows> byStatus(ByStatus query) {
    return queryResult();
  }
}

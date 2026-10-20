package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.CaseEvent;
import io.akka.contextlayer.domain.CaseFile;
import java.util.List;

/** One row per case, for work queues and lists. */
@Component(id = "case-view")
public class CaseView extends View {

  public record CaseRow(
      String orgId, String caseId, String monitorId, String key, String status, String routeTo, long openedAt) {

    CaseRow withStatus(CaseFile.Status next) {
      return new CaseRow(orgId, caseId, monitorId, key, next.name(), routeTo, openedAt);
    }
  }

  public record CaseRows(List<CaseRow> items) {}

  public record ByStatus(String orgId, String status) {}

  @Consume.FromEventSourcedEntity(CaseEntity.class)
  public static class Updater extends TableUpdater<CaseRow> {
    public Effect<CaseRow> onEvent(CaseEvent event) {
      return switch (event) {
        case CaseEvent.Opened e -> effects().updateRow(new CaseRow(
            e.orgId(), e.caseId(), e.monitorId(), e.key(), CaseFile.Status.OPEN.name(), e.routeTo(), e.at()));
        case CaseEvent.NoLongerMatching e -> effects().updateRow(rowState().withStatus(CaseFile.Status.NO_LONGER_MATCHING));
        case CaseEvent.Resolved e -> effects().updateRow(rowState().withStatus(CaseFile.Status.RESOLVED));
        case CaseEvent.Dismissed e -> effects().updateRow(rowState().withStatus(CaseFile.Status.DISMISSED));
        case CaseEvent.Annotated e -> effects().ignore();
      };
    }
  }

  @Query("SELECT * AS items FROM cases WHERE orgId = :orgId AND status = :status")
  public QueryEffect<CaseRows> byStatus(ByStatus query) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM cases WHERE orgId = :orgId")
  public QueryEffect<CaseRows> forOrganization(String orgId) {
    return queryResult();
  }
}

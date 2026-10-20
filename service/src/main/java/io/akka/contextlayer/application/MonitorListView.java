package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.MonitorDefinition;
import io.akka.contextlayer.domain.MonitorEvent;
import java.util.List;

/** The monitors of an organization with their last run, for lists. */
@Component(id = "monitor-list-view")
public class MonitorListView extends View {

  public record MonitorRow(
      String orgId,
      String monitorId,
      String name,
      boolean enabled,
      int intervalMinutes,
      String assistantId,
      int runCount,
      long lastRunAt,
      String lastRunNote,
      boolean onChange,
      List<String> entityTypes) {

    MonitorRow withDefinition(MonitorDefinition d) {
      return new MonitorRow(orgId, monitorId, d.name(), enabled, d.intervalMinutes(), d.assistantId(), runCount,
          lastRunAt, lastRunNote, d.onChange(), d.entityTypes());
    }

    MonitorRow withEnabled(boolean next) {
      return new MonitorRow(orgId, monitorId, name, next, intervalMinutes, assistantId, runCount, lastRunAt, lastRunNote,
          onChange, entityTypes);
    }

    MonitorRow withRun(int runs, long at, String note) {
      return new MonitorRow(orgId, monitorId, name, enabled, intervalMinutes, assistantId, runs, at, note, onChange,
          entityTypes);
    }
  }

  public record MonitorRows(List<MonitorRow> items) {}

  @Consume.FromEventSourcedEntity(MonitorEntity.class)
  public static class Updater extends TableUpdater<MonitorRow> {
    public Effect<MonitorRow> onEvent(MonitorEvent event) {
      return switch (event) {
        case MonitorEvent.Created e -> effects().updateRow(new MonitorRow(e.orgId(), e.monitorId(),
            e.definition().name(), true, e.definition().intervalMinutes(), e.definition().assistantId(), 0, 0, "", e.definition().onChange(),
            e.definition().entityTypes()));
        case MonitorEvent.DefinitionUpdated e -> effects().updateRow(rowState().withDefinition(e.definition()));
        case MonitorEvent.EnabledChanged e -> effects().updateRow(rowState().withEnabled(e.enabled()));
        case MonitorEvent.RunRecorded e -> effects().updateRow(rowState().withRun(e.runNumber(), e.at(), e.note()));
      };
    }
  }

  @Query("SELECT * AS items FROM monitors WHERE orgId = :orgId")
  public QueryEffect<MonitorRows> forOrganization(String orgId) {
    return queryResult();
  }
}

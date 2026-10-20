package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.ConnectorPackEvent;
import java.util.List;

/**
 * The connector packs of an organization and their mode. It only lists names: callers read the
 * pack entity itself for the definition, because a view lags its entity.
 */
@Component(id = "connector-pack-view")
public class ConnectorPackView extends View {

  public record ConnectorRow(String orgId, String name, String mode) {}

  public record ConnectorRows(List<ConnectorRow> items) {}

  @Consume.FromEventSourcedEntity(ConnectorPackEntity.class)
  public static class Updater extends TableUpdater<ConnectorRow> {
    public Effect<ConnectorRow> onEvent(ConnectorPackEvent event) {
      return switch (event) {
        case ConnectorPackEvent.Imported e -> {
          var current = rowState();
          yield effects().updateRow(current == null
              ? new ConnectorRow(e.orgId(), e.name(), ConnectorPack.Mode.INACTIVE.name())
              : current);
        }
        case ConnectorPackEvent.Activated e ->
            effects().updateRow(new ConnectorRow(rowState().orgId(), rowState().name(), e.mode().name()));
        case ConnectorPackEvent.Deactivated e ->
            effects().updateRow(new ConnectorRow(rowState().orgId(), rowState().name(),
                ConnectorPack.Mode.INACTIVE.name()));
      };
    }
  }

  @Query("SELECT * AS items FROM connectors WHERE orgId = :orgId")
  public QueryEffect<ConnectorRows> forOrganization(String orgId) {
    return queryResult();
  }
}

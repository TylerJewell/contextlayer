package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/** Notifications by organization, newest first. */
@Component(id = "delivery-view")
public class DeliveryView extends View {

  public record Row(String deliveryId, String orgId, String route, String type, String subject, String status,
      String detail, long at) {}

  public record Rows(List<Row> items) {}

  public record ForOrganization(String orgId, int max) {}

  @Consume.FromEventSourcedEntity(DeliveryEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onEvent(DeliveryEntity.Event event) {
      return switch (event) {
        case DeliveryEntity.Event.Recorded r -> {
          var d = r.delivery();
          yield effects().updateRow(new Row(d.deliveryId(), d.orgId(), d.route(), d.type(), d.subject(), d.status(),
              d.detail(), d.at()));
        }
      };
    }
  }

  @Query("""
      SELECT * AS items FROM deliveries
      WHERE orgId = :orgId
      ORDER BY at DESC
      LIMIT :max
      """)
  public QueryEffect<Rows> forOrganization(ForOrganization query) {
    return queryResult();
  }
}

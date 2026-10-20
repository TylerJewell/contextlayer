package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import java.time.Instant;

/**
 * Sends a message to a route and records how it went. A route nobody set up is recorded as such
 * (NO_ROUTE) so that "nobody was told" is visible instead of silent.
 */
public final class Notifications {
  private Notifications() {}

  public static DeliveryEntity.Delivery send(
      ComponentClient client, Notifier notifier, String orgId, String route, String subject, Notifier.Message message) {
    var state = client.forKeyValueEntity(NotificationRouteEntity.id(orgId, route))
        .method(NotificationRouteEntity::get).invoke();
    var id = ScopedId.of(orgId, "nt", ScopedId.safe(subject));
    String status;
    String detail;
    if (!state.exists() || !state.enabled()) {
      status = "NO_ROUTE";
      detail = state.exists() ? "the route is switched off" : "no delivery is set up for this route";
    } else {
      var result = notifier.send(state.route(), message, id);
      status = result.delivered() ? "DELIVERED" : "FAILED";
      detail = result.detail();
    }
    var delivery = new DeliveryEntity.Delivery(id, orgId, route, message.type(), subject, status, detail,
        Instant.now().toEpochMilli());
    client.forEventSourcedEntity(id).method(DeliveryEntity::record).invoke(delivery);
    return delivery;
  }
}

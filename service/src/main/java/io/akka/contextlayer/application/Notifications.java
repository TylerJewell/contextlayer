package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.PersonalDataMasker;
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
      recordGuards(client, orgId, route, subject, message, detail);
    }
    var delivery = new DeliveryEntity.Delivery(id, orgId, route, message.type(), subject, status, detail,
        Instant.now().toEpochMilli());
    client.forEventSourcedEntity(id).method(DeliveryEntity::record).invoke(delivery);
    return delivery;
  }

  /**
   * A message leaving the service passes two controls: its title and summary are masked and it carries ids only
   * (custom-cl0018), and its host or mail server is on the egress list (custom-cl0009). The notifiers enforce both;
   * this records what they did.
   */
  private static void recordGuards(
      ComponentClient client, String orgId, String route, String subject, Notifier.Message message, String detail) {
    var verdicts = new Verdicts(client);
    var part = Verdicts.once(route);
    var what = "message to " + route + " about " + subject;
    boolean refused = detail != null && detail.endsWith(SourceCaller.EGRESS_REFUSAL);
    verdicts.guardrail(orgId, Controls.EGRESS, part, !refused, refused ? "host-not-allowed" : "host-allowed", what,
        "system:notifier");
    if (refused) return;
    int masked = PersonalDataMasker.mask(message.title() == null ? "" : message.title()).total()
        + PersonalDataMasker.mask(message.summary() == null ? "" : message.summary()).total();
    verdicts.sanitizer(orgId, Controls.NOTIFICATION_MASK, part, masked, what, "system:notifier");
  }
}

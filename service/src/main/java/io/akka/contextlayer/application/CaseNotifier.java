package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.domain.CaseEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Tells the team a new case is routed to. The message names the case and the monitor and nothing
 * from the evidence; the team opens the console for that. What happened is written on the case
 * timeline, including that nobody could be told.
 */
@Component(id = "case-notifier")
@Consume.FromEventSourcedEntity(CaseEntity.class)
public class CaseNotifier extends Consumer {

  private final ComponentClient componentClient;
  private final Notifier notifier;

  public CaseNotifier(ComponentClient componentClient, Notifier notifier) {
    this.componentClient = componentClient;
    this.notifier = notifier;
  }

  public Effect onEvent(CaseEvent event) {
    if (!(event instanceof CaseEvent.Opened opened) || opened.routeTo() == null || opened.routeTo().isBlank()) {
      return effects().done();
    }
    var message = new Notifier.Message("case.opened", opened.orgId(), opened.routeTo(),
        "New case for " + opened.key(),
        "Watcher " + opened.monitorId() + " found " + opened.key() + "; it is routed to " + opened.routeTo()
            + ". Open it in the console.",
        Map.of("caseId", opened.caseId(), "monitorId", opened.monitorId()), null,
        Instant.ofEpochMilli(opened.at()).toString());
    var delivery = Notifications.send(componentClient, notifier, opened.orgId(), opened.routeTo(),
        "case:" + opened.caseId(), message);
    try {
      componentClient.forEventSourcedEntity(opened.caseId()).method(CaseEntity::annotate).invoke(new CaseEntity.Annotate(
          "notifications", "notified " + opened.routeTo() + ": " + ("DELIVERED".equals(delivery.status())
              ? delivery.detail()
              : delivery.status().toLowerCase().replace('_', ' ') + " (" + delivery.detail() + ")"),
          System.currentTimeMillis()));
    } catch (RuntimeException e) {
      // The timeline is for people; the delivery is recorded either way.
    }
    return effects().done();
  }
}

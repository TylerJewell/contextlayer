package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.governance.HumanEventEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Tells the people an event waits on that it needs them: when it is raised, and again when it is
 * escalated to the next role. The route is {@code role:<role>}, so a team sets up one destination
 * per role.
 */
@Component(id = "human-event-notifier")
@Consume.FromEventSourcedEntity(HumanEventEntity.class)
public class HumanEventNotifier extends Consumer {

  /** Detail key of an event decided in the same call that raised it. */
  public static final String IMMEDIATE = "_immediate";

  private final ComponentClient componentClient;
  private final Notifier notifier;

  public HumanEventNotifier(ComponentClient componentClient, Notifier notifier) {
    this.componentClient = componentClient;
    this.notifier = notifier;
  }

  public Effect onEvent(HumanEventEvent event) {
    boolean raised = event instanceof HumanEventEvent.Raised;
    boolean escalated = event instanceof HumanEventEvent.Escalated;
    if (!raised && !escalated) return effects().done();

    var id = messageContext().eventSubject().orElseThrow();
    var human = componentClient.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke();
    if (human.detail().containsKey(IMMEDIATE)) return effects().done();
    var roles = human.currentRoles();
    if (roles.isEmpty()) return effects().done();

    var route = "role:" + roles.get(0);
    var message = new Notifier.Message(raised ? "approval.requested" : "approval.escalated", human.orgId(), route,
        (raised ? "Needs you: " : "Escalated to you: ") + human.summary(), human.question(),
        Map.of("eventId", human.id(), "control", human.controlId()),
        Instant.ofEpochMilli(human.dueAt()).toString(), Instant.now().toString());
    Notifications.send(componentClient, notifier, human.orgId(), route,
        "event:" + human.id() + "~level" + human.level(), message);
    return effects().done();
  }
}

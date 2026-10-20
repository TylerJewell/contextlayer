package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import java.time.Duration;

/**
 * Times a human event out when its service level runs out. Events that wait on a workflow are timed
 * out by that workflow; this is for the ones nothing else is watching. The event decides whether it
 * is really due and whether anyone is still to act, so a timer that fires late, twice or for an
 * event already decided changes nothing.
 */
@Component(id = "human-event-timed-action")
public class HumanEventTimedAction extends TimedAction {

  public record Expiry(String eventId) {}

  private final ComponentClient componentClient;

  public HumanEventTimedAction(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public static void schedule(
      TimerScheduler timers, ComponentClient componentClient, String eventId, long dueAt) {
    // A second past due, because the event refuses to expire until the due time has passed.
    var delay = Math.max(0, dueAt + 1000 - System.currentTimeMillis());
    timers.createSingleTimer("he-expiry-" + eventId, Duration.ofMillis(delay),
        componentClient.forTimedAction().method(HumanEventTimedAction::expire).deferred(new Expiry(eventId)));
  }

  public Effect expire(Expiry expiry) {
    componentClient.forEventSourcedEntity(expiry.eventId()).method(HumanEventEntity::expire)
        .invoke(new HumanEventEntity.Expire(System.currentTimeMillis()));
    return effects().done();
  }
}

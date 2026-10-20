package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.ScopedId;
import java.time.Duration;

/**
 * Starts one run of a monitor at its scheduled time and schedules the next. The run's workflow id
 * comes from the scheduled time, so if this action is retried it starts nothing twice. A monitor
 * that is missing or switched off ends the chain; enabling it schedules a new one.
 */
@Component(id = "monitor-timed-action")
public class MonitorTimedAction extends TimedAction {

  /** @param scheduledAt epoch millis this run was due */
  public record Tick(String orgId, String monitorId, long scheduledAt) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public MonitorTimedAction(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  public static String timerName(String orgId, String monitorId) {
    return "monitor-" + ScopedId.of(orgId, monitorId);
  }

  /** Runs a monitor because the data it reads changed. This does not touch the schedule. */
  public Effect changed(Tick tick) {
    var monitor = componentClient.forEventSourcedEntity(ScopedId.of(tick.orgId(), tick.monitorId()))
        .method(MonitorEntity::get).invoke();
    if (!monitor.exists() || !monitor.enabled()) return effects().done();
    componentClient.forWorkflow(ScopedId.of(tick.orgId(), tick.monitorId(), "c" + tick.scheduledAt()))
        .method(MonitorRunWorkflow::start)
        .invoke(new MonitorRunWorkflow.Start(tick.orgId(), tick.monitorId(), tick.scheduledAt()));
    return effects().done();
  }

  public Effect tick(Tick tick) {
    var monitor = componentClient.forEventSourcedEntity(ScopedId.of(tick.orgId(), tick.monitorId()))
        .method(MonitorEntity::get).invoke();
    if (!monitor.exists() || !monitor.enabled()) return effects().done();

    var nextAt = tick.scheduledAt() + Duration.ofMinutes(monitor.definition().intervalMinutes()).toMillis();
    timerScheduler.createSingleTimer(
        timerName(tick.orgId(), tick.monitorId()),
        Duration.ofMillis(Math.max(0, nextAt - System.currentTimeMillis())),
        componentClient.forTimedAction().method(MonitorTimedAction::tick)
            .deferred(new Tick(tick.orgId(), tick.monitorId(), nextAt)));

    componentClient.forWorkflow(ScopedId.of(tick.orgId(), tick.monitorId(), "t" + tick.scheduledAt()))
        .method(MonitorRunWorkflow::start)
        .invoke(new MonitorRunWorkflow.Start(tick.orgId(), tick.monitorId(), tick.scheduledAt()));
    return effects().done();
  }
}

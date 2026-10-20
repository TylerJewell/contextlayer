package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.ScopedId;
import java.time.Duration;
import java.util.Map;

/**
 * Reads one MAINTAINED entity type from its source, then schedules the next read. The next read is
 * scheduled first, so a read that fails or throws does not end the chain. A connector that is gone
 * or inactive, or a type no longer MAINTAINED, ends it; activating the connector starts a new one.
 *
 * <p>A timer is unique by its name, so starting a chain that is already running replaces it.
 */
@Component(id = "maintained-refresh")
public class MaintainedRefreshAction extends TimedAction {

  /** @param scheduledAt epoch millis this read was due */
  public record Tick(String orgId, String pack, String entityType, long scheduledAt) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;
  private final EntityRefresher refresher;

  public MaintainedRefreshAction(
      ComponentClient componentClient, TimerScheduler timerScheduler, SourceCaller sourceCaller, EntityStore store) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
    this.refresher = new EntityRefresher(componentClient, sourceCaller, store, System::currentTimeMillis);
  }

  static String timerName(String orgId, String pack, String entityType) {
    return "maintain-" + ScopedId.of(orgId, pack, entityType);
  }

  static boolean maintained(PackDefinition.EntityMapping entity) {
    return entity.freshness() != null && "MAINTAINED".equals(entity.freshness().policy());
  }

  /** Starts a chain of reads for each MAINTAINED type of an active connector, the first one now. */
  public static void start(TimerScheduler timers, ComponentClient componentClient, String orgId, ConnectorPack pack) {
    if (pack.active().isEmpty() || pack.active().get().definition().entities() == null) return;
    long now = System.currentTimeMillis();
    for (var entity : pack.active().get().definition().entities()) {
      if (maintained(entity)) {
        timers.createSingleTimer(timerName(orgId, pack.name(), entity.type()), Duration.ZERO,
            componentClient.forTimedAction().method(MaintainedRefreshAction::tick)
                .deferred(new Tick(orgId, pack.name(), entity.type(), now)));
      }
    }
  }

  /** Ends the chains of a connector that is being switched off. */
  public static void stop(TimerScheduler timers, String orgId, ConnectorPack pack) {
    if (pack.active().isEmpty() || pack.active().get().definition().entities() == null) return;
    for (var entity : pack.active().get().definition().entities()) {
      if (maintained(entity)) timers.delete(timerName(orgId, pack.name(), entity.type()));
    }
  }

  public Effect tick(Tick tick) {
    var pack = componentClient.forEventSourcedEntity(ScopedId.of(tick.orgId(), tick.pack()))
        .method(ConnectorPackEntity::get).invoke();
    if (!pack.exists() || pack.active().isEmpty() || pack.active().get().definition().entities() == null) {
      return effects().done();
    }
    var entity = pack.active().get().definition().entities().stream()
        .filter(e -> e.type().equals(tick.entityType()) && maintained(e)).findFirst().orElse(null);
    if (entity == null) return effects().done();

    long now = System.currentTimeMillis();
    long nextAt = Math.max(tick.scheduledAt() + Duration.ofMinutes(entity.freshness().ttlMinutes()).toMillis(), now);
    timerScheduler.createSingleTimer(
        timerName(tick.orgId(), tick.pack(), tick.entityType()),
        Duration.ofMillis(nextAt - now),
        componentClient.forTimedAction().method(MaintainedRefreshAction::tick)
            .deferred(new Tick(tick.orgId(), tick.pack(), tick.entityType(), nextAt)));

    var status = refresher.refresh(tick.orgId(), tick.pack(), tick.entityType(), Map.of());
    if (status.outcome() != RefreshStatus.Outcome.FAILED) {
      ChangeFeed.changed(componentClient, timerScheduler, tick.orgId(),
          new ChangeFeedEntity.ChangeNotice(tick.pack(), tick.entityType(), "", "refreshed", now));
    }
    return effects().done();
  }
}
